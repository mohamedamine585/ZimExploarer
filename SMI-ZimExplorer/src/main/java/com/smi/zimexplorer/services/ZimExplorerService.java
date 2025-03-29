package com.smi.zimexplorer.services;

import com.smi.zimexplorer.entities.IMail;
import com.smi.zimexplorer.repositories.IMailRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import javax.mail.*;
import javax.mail.event.MessageCountListener;
import javax.mail.internet.MimeMultipart;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ZimExplorerService {

    private final IMailRepository iMailRepository;
    private final Environment env;
    private final Logger logger = LoggerFactory.getLogger(ZimExplorerService.class);

    private String pop3Host;
    private String pop3Port;
    private String attachmentsPath;
    private String email;
    private String password;
    private Integer cronRate;

    private TaskScheduler taskScheduler;
    private ScheduledFuture<?> scheduledTask;
    private Store store;
    private Folder inbox;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private static final String INVALID_CHARACTERS_REGEX = "[<>:\"/|?*]";
    private static final Pattern pattern = Pattern.compile(INVALID_CHARACTERS_REGEX);
    private static final Pattern NUMBER_MESSAGE_PATTERN = Pattern.compile("<NUMERO_MESSAGE>(.*?)</NUMERO_MESSAGE>");

    @Autowired
    public ZimExplorerService(IMailRepository iMailRepository, Environment env) {
        this.iMailRepository = iMailRepository;
        this.env = env;
        init();
        mailFetchScheduler();
    }

    private void init() {
        pop3Host = env.getProperty("mail.pop3.host");
        pop3Port = env.getProperty("mail.pop3.port");
        attachmentsPath = env.getProperty("attachments.path");
        email = env.getProperty("mail.pop3.username");
        password = env.getProperty("mail.pop3.password");
        cronRate = Integer.parseInt(env.getProperty("cronRate", "60000")); // Default to 60s
    }




    private void connectToMailServer() throws MessagingException {
        closeConnection(); // Ensure old connections are closed first

        Properties properties = new Properties();
        properties.put("mail.store.protocol", "pop3s");
        properties.put("mail.pop3.host", pop3Host);
        properties.put("mail.pop3.port", pop3Port);
        properties.put("mail.pop3.starttls.enable", "true");
        properties.put("mail.pop3.ssl.enable", "true");

        Session session = Session.getInstance(properties);
        store = session.getStore("pop3s");
        store.connect(pop3Host, email, password);

        inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY);
    }

    public void mailFetchScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        this.taskScheduler = scheduler;
        startScheduledTask();
    }

    private void startScheduledTask() {
        if (scheduledTask != null && !scheduledTask.isDone()) {
            scheduledTask.cancel(false);
        }
        scheduledTask = taskScheduler.scheduleAtFixedRate(this::fetchEmails, cronRate);
    }

    private void refreshInbox() throws MessagingException {
        if (inbox != null && inbox.isOpen()) {
            inbox.close(false);
        }
        if (store != null && store.isConnected()) {
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
        } else {
            connectToMailServer(); // Reconnect if store is disconnected
        }
    }
    public void fetchEmails() {
        if (isProcessing.getAndSet(true)) {
            return;
        }

        try {
            connectToMailServer(); // Reconnect (old connections are closed first)
            Message[] messages = inbox.getMessages();

            for (int i = messages.length - 1; i >= 0; i--) {
                try {
                    processEmail(messages[i]);
                } catch (Exception e) {
                    if (e.getMessage().equals("Mail already processed") && i == messages.length - 1) {
                        logger.warn("No new mails to process");
                    } else if(!e.getMessage().equals("Mail already processed")){
                        logger.warn("Error Processing Email: {}", e.getMessage());
                    }
                }
            }
        } catch (MessagingException e) {
            logger.error("Error fetching emails: {}", e.getMessage());
        } finally {
            closeConnection(); // Always close connection after processing
            isProcessing.set(false);
        }
    }
    @PreDestroy
    private void closeConnection() {
        try {
            if (inbox != null && inbox.isOpen()) {
                inbox.close(false);
            }
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (Exception e) {
            logger.error("Error closing connection: {}", e.getMessage());
        } finally {
            inbox = null;
            store = null;
        }
    }
    private void processEmail(Message message) throws Exception {
        String status = "STARTED";
        String messageId = "";
        try {
            messageId = extractMessageId(message);


            List<IMail> existingMails = iMailRepository.findByMessageId(messageId) ;
            if(!existingMails.isEmpty()){
                throw new Exception("Mail already processed");

            }





            List<String> emailAttachmentPaths = new ArrayList<>();
            status = "PROCESSING";
            logStatus(status, messageId);


            Object content = message.getContent();
            StringBuilder emailBody = new StringBuilder();

            if (content instanceof MimeMultipart) {
                MimeMultipart outerMultipart = (MimeMultipart) content;
                Object innerContent = outerMultipart.getBodyPart(0).getContent();

                if (innerContent instanceof MimeMultipart) {
                    MimeMultipart innerMultipart = (MimeMultipart) innerContent;
                    Object finalContent = innerMultipart.getBodyPart(0).getContent();

                    if(emailBody.isEmpty()){
                        emailBody.append(finalContent instanceof String ? (String) finalContent : finalContent.toString());

                    }
                } else {
                    emailBody.append(innerContent.toString());
                }
            } else {
                emailBody.append(content.toString());
            }

            String finalOutput = emailBody.toString();
            if (message.isMimeType("text/plain")) {
                if(emailBody.isEmpty()){
                    emailBody.append(message.getContent().toString());
                }
            } else if (message.isMimeType("multipart/*")) {


                emailAttachmentPaths = processMultipart((Multipart) message.getContent(), emailBody);
            }

            String from = Arrays.stream(message.getFrom())
                    .map(Object::toString)
                    .map(f -> f.contains("<") ? f.substring(f.indexOf("<") + 1, f.indexOf(">")) : f)
                    .findFirst().orElse("");

            IMail iMail = new IMail();
            iMail.setSender(from);
            iMail.setMessageId(messageId);
            iMail.setBody(emailBody.toString());
            iMail.setAttachmentsPath("");
            // Handle potential null subject
            iMail.setSubject(message.getSubject() != null ?
                    message.getSubject() : "[No Subject]");

            iMail.setReceivedAt(LocalDateTime.ofInstant(message.getSentDate().toInstant(), ZoneId.systemDefault()));
            try {
                iMailRepository.save(iMail);

            }catch (Exception e){
                throw new SQLException();
            }
            for(int i = 0 ; i < emailAttachmentPaths.size() ; i++){
                iMail = new IMail();
                iMail.setSender(from);
                iMail.setMessageId(messageId);
                iMail.setBody("");
                iMail.setAttachmentsPath(emailAttachmentPaths.get(i));
                // Handle potential null subject
                iMail.setSubject(message.getSubject() != null ?
                        message.getSubject() : "[No Subject]");

                iMail.setReceivedAt(LocalDateTime.ofInstant(message.getSentDate().toInstant(), ZoneId.systemDefault()));
                try {
                    iMailRepository.save(iMail);

                }catch (Exception e){
                    throw new SQLException();
                }
            }
            status = "COMPLETED";

        }

        catch (SQLException e){

            return;
        }
        finally {
            logStatus(status, messageId);
        }
    }

    private void logStatus(String status, String messageId) {
        String icon = "🔄"; // Default processing icon
        if (status.startsWith("COMPLETED")) {
            icon = "✅";
        }
        else if(!status.startsWith("PROCESSING")){
            return;
        }

        // Show first 4 chars of message ID for security

        logger.info("{} | {} | ID: {}", icon, status, messageId);
    }

    private List<String> processMultipart(Multipart multipart, StringBuilder emailBody) throws MessagingException, IOException {
        List<String> emailAttachmentPaths = new ArrayList<>();


        String numMessage = extractNumMessage(emailBody.toString());
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            if (bodyPart.isMimeType("text/plain")) {
                if(emailBody.isEmpty()){
                    emailBody.append(bodyPart.getContent().toString());

                }
            }
            else if (bodyPart.isMimeType("text/html")) {
                String fileName = numMessage + "_" + UUID.randomUUID().toString() + ".html";
                emailAttachmentPaths.add(saveHtmlContent(fileName,bodyPart));
            } else if (Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition()) || bodyPart.getFileName() != null) {
                String fileName =  bodyPart.getFileName();
                Matcher matcher = pattern.matcher(fileName);

                if(matcher.find()){

                    StringBuilder extension = new StringBuilder();
                    for(int idx = fileName.lastIndexOf(".") ; idx < fileName.length() ; idx++){
                        Matcher matcher1 = pattern.matcher(extension.toString() + fileName.charAt(idx));
                        if(matcher1.find()){
                            break;
                        }
                        extension.append(fileName.charAt(idx));

                    }
                    fileName = UUID.randomUUID().toString() + extension;
                }
                fileName = numMessage + "_" + fileName;
                emailAttachmentPaths.add(saveAttachment(fileName, bodyPart));
            }

        }
        return emailAttachmentPaths;

    }

    private String saveHtmlContent(String fileName,BodyPart bodyPart) throws MessagingException, IOException {
        return saveFile(fileName, bodyPart);
    }

    private String saveAttachment(String fileName,BodyPart bodyPart) throws MessagingException, IOException {


       return   saveFile(fileName,bodyPart);
    }

    private String saveFile(String fileName, BodyPart bodyPart) throws MessagingException, IOException {

        Path filePath = Path.of(attachmentsPath,fileName);
        Files.write(filePath, bodyPart.getInputStream().readAllBytes());
        return filePath.toString();
    }

    private String extractMessageId(Message message) throws MessagingException {
        return Optional.ofNullable(message.getHeader("MESSAGE-ID"))
                .filter(ids -> ids.length > 0)
                .map(ids -> ids[0].replaceAll("[<>]", ""))
                .map(id -> id.contains("@") ? id.substring(0, id.indexOf("@")) : id)
                .orElse(UUID.randomUUID().toString());
    }
    private String extractNumMessage(String bodyContent) {
        Matcher matcher = NUMBER_MESSAGE_PATTERN.matcher(bodyContent);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }
        return "LU_" + UUID.randomUUID(); // Fallback if no number is found
    }

}
