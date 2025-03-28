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
    Pattern pattern = Pattern.compile(INVALID_CHARACTERS_REGEX);
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




    private void connectToMailServer() {
        try {
            Properties properties = new Properties();
            properties.put("mail.store.protocol", "pop3");
            properties.put("mail.pop3s.host", pop3Host);
            properties.put("mail.pop3s.port", pop3Port);
            properties.put("mail.pop3.starttls.enable", "true"); // Ensure STARTTLS is off
            properties.put("mail.pop3.ssl.enable", "true");

            Session session = Session.getDefaultInstance(properties);
            store = session.getStore("pop3s");
            store.connect(pop3Host, email, password);

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
        } catch (Exception e) {
            logger.error("POP3 CONNECTION ERROR! CHECK YOUR CREDENTIALS", e);
        }
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

    public void fetchEmails() {
        if (isProcessing.getAndSet(true)) {
            return;
        }

        try {
            if (store == null || !store.isConnected()  || inbox == null || !inbox.isOpen()) {
                connectToMailServer();
            }
            if(inbox == null || !inbox.isOpen()){
                isProcessing.set(false);
                return;
            }


            Message[] messages = inbox.getMessages();
            for (int i = messages.length - 1; i >= 0; i--) {
                try {
                    processEmail(messages[i]);
                } catch (Exception e) {
                    if (!"Mail already processed".equals(e.getMessage()) ) {
                        logger.error("Error processing email: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching emails: {}", e.getMessage());
        } finally {
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
        }
    }
    private void processEmail(Message message) throws Exception {
        String status = "STARTED";
        String messageId = "";
        Path messageFolderPath = null ;
        try {
            messageId = extractMessageId(message);


            IMail iMail = iMailRepository.findByMessageId(messageId) ;
            if(iMail != null){
                throw new Exception("Mail already processed");

            }





            status = "PROCESSING";
            logStatus(status, messageId);

            StringBuilder emailBody = new StringBuilder();
            if (message.isMimeType("text/plain")) {
                emailBody.append(message.getContent().toString());
            } else if (message.isMimeType("multipart/*")) {
                 messageFolderPath = Path.of(attachmentsPath, "email_" + messageId);
                if (Files.isDirectory(messageFolderPath)) {
                    throw new Exception("Mail already processed");
                }
                processMultipart((Multipart) message.getContent(), emailBody, messageFolderPath);
            }

            String from = Arrays.stream(message.getFrom())
                    .map(Object::toString)
                    .map(f -> f.contains("<") ? f.substring(f.indexOf("<") + 1, f.indexOf(">")) : f)
                    .findFirst().orElse("");

            iMail = new IMail();
            iMail.setSender(from);
            iMail.setMessageId(messageId);
            iMail.setBody(emailBody.toString());
            iMail.setAttachmentsPath(messageFolderPath != null ? messageFolderPath.toString() : "");
            // Handle potential null subject
            iMail.setSubject(message.getSubject() != null ?
                    message.getSubject() : "[No Subject]");

            iMail.setReceivedAt(LocalDateTime.ofInstant(message.getSentDate().toInstant(), ZoneId.systemDefault()));
            try {
                iMailRepository.save(iMail);

            }catch (Exception e){
                throw new SQLException();
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

    private void processMultipart(Multipart multipart, StringBuilder emailBody, Path messageFolderPath) throws MessagingException, IOException {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            if (bodyPart.isMimeType("text/plain")) {
                emailBody.append(bodyPart.getContent().toString());
            } else if (bodyPart.isMimeType("text/html")) {
                Files.createDirectories(messageFolderPath);
                saveHtmlContent(bodyPart, messageFolderPath);
            } else if (Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition()) || bodyPart.getFileName() != null) {
                Files.createDirectories(messageFolderPath);
                saveAttachment(bodyPart.getFileName(), bodyPart, messageFolderPath);
            }
        }
    }

    private void saveHtmlContent(BodyPart bodyPart, Path messageFolderPath) throws MessagingException, IOException {
        String fileName = UUID.randomUUID().toString() + ".html";
        saveFile(fileName, bodyPart, messageFolderPath);
    }

    private void saveAttachment(String fileName,BodyPart bodyPart, Path messageFolderPath) throws MessagingException, IOException {


        saveFile(fileName,bodyPart, messageFolderPath);
    }

    private void saveFile(String fileName, BodyPart bodyPart, Path messageFolderPath) throws MessagingException, IOException {
        Matcher matcher = pattern.matcher(fileName);

        if(matcher.find()){
            fileName = UUID.randomUUID().toString();
        }
        Path filePath = messageFolderPath.resolve(fileName);
        Files.write(filePath, bodyPart.getInputStream().readAllBytes());
    }

    private String extractMessageId(Message message) throws MessagingException {
        return Optional.ofNullable(message.getHeader("MESSAGE-ID"))
                .filter(ids -> ids.length > 0)
                .map(ids -> ids[0].replaceAll("[<>]", ""))
                .map(id -> id.contains("@") ? id.substring(0, id.indexOf("@")) : id)
                .orElse(UUID.randomUUID().toString());
    }
}
