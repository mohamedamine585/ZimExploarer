package com.smi.zimexplorer.services;

import com.smi.zimexplorer.entities.IMail;
import com.smi.zimexplorer.exceptions.MailAlreadyProcessed;
import com.smi.zimexplorer.repositories.IMailRepository;
import jakarta.annotation.PreDestroy;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import javax.mail.*;
import javax.mail.event.MessageCountListener;
import javax.mail.internet.MimeMultipart;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private static final Logger logger = LogManager.getLogger(ZimExplorerService.class);

    private String pop3Host;
    private String pop3Port;
    private String attachmentsPath,failedMessagesPath;
    private String email;
    private String password;
    private Integer cronRate;
    private Boolean deleteInboxMessages = false;

    private TaskScheduler taskScheduler;
    private ScheduledFuture<?> scheduledTask;
    private Store store;
    private Folder inbox;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private static final String INVALID_CHARACTERS_REGEX = "[<>:\"/|?*]";
    private static final Pattern pattern = Pattern.compile(INVALID_CHARACTERS_REGEX);
    private static final Pattern NUMBER_MESSAGE_PATTERN = Pattern.compile("<NUMERO_MESSAGE>(.*?)</NUMERO_MESSAGE>");
    private File errorLogFile;

    @Autowired
    public ZimExplorerService(IMailRepository iMailRepository, Environment env) throws Exception{
        this.iMailRepository = iMailRepository;
        this.env = env;
        init();
        mailFetchScheduler();
    }

    private static final long MAX_FREE_SPACE = 4096; // 4 KB (4096 bytes)



    // Get available free space on the filesystem of the log file's directory
    private static long getFreeSpace(File logFile) {
        File parentDir = logFile.getParentFile();
        return parentDir.getUsableSpace(); // Returns the free space in bytes
    }

    // Clean up the log file by deleting the last 100 lines
    private static void cleanUpLogs(File logFile) throws IOException {
        // Read all lines from the log file
        List<String> allLines = Files.readAllLines(logFile.toPath());
        if (allLines.size() > 100) {
            // Remove the last 100 lines
            List<String> linesToKeep = allLines.subList(0, allLines.size() - 100);

            // Write the remaining lines back to the file
            Files.write(logFile.toPath(), linesToKeep, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("Successfully deleted the latest 100 log entries.");
        } else {
            // If there are fewer than 100 lines, clear the entire file
            Files.write(logFile.toPath(), Collections.emptyList(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("Log file had fewer than 100 lines. File cleared.");
        }
    }

    private void init() throws Exception{
        pop3Host = env.getProperty("mail.pop3.host");
        pop3Port = env.getProperty("mail.pop3.port");
        email = env.getProperty("mail.pop3.username");
        password = env.getProperty("mail.pop3.password");
        cronRate = Integer.parseInt(env.getProperty("cronRate", "60000")); // Default to 60s
        attachmentsPath = env.getProperty("attachments.path");
        failedMessagesPath = env.getProperty("failedMessages.path");
        if(env.getProperty("deleteInboxMessages") != null){
            deleteInboxMessages =  env.getProperty("deleteInboxMessages").equalsIgnoreCase("true");

        }

        if(attachmentsPath == null){
            logger.error("Attachment path specified is null");
            throw new NoSuchFileException("Attachment path specified is null");
        }
        File attachmentDir = new File(attachmentsPath);
        if(!attachmentDir.exists() || !attachmentDir.isDirectory()){
            logger.error("Attachment path specified does not exist");
            throw new NoSuchFileException("Attachment path specified does not exist");
        }
        if(failedMessagesPath == null){
            logger.error("Failed messages path specified is null");
            throw new NoSuchFileException("Failed messages specified is null");
        }
        File failedDir = new File(failedMessagesPath);
        if(!failedDir.exists() || !failedDir.isDirectory()){
            logger.error("Failed messages path specified does not exist");
            throw new NoSuchFileException("Failed messages path specified does not exist");
        }
    }




    private void connectToMailServer() throws MessagingException {
        try {

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
            inbox.open(Folder.READ_WRITE);
        }catch (Exception e) {
            logger.error("Error connecting to mail server: {}", e.getMessage());

            throw  e;
        }

    }

    public void mailFetchScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        this.taskScheduler = scheduler;
        startScheduledTask();
    }

    private void startScheduledTask() {
        try {
            if (scheduledTask != null && !scheduledTask.isDone()) {
                scheduledTask.cancel(false);
            }
            connectToMailServer(); // Reconnect (old connections are closed first)

            scheduledTask = taskScheduler.scheduleAtFixedRate(this::fetchEmails, cronRate);
        }catch (Exception e){
            logger.error("Cannot schedule task : {}",e.getMessage());
        }

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

        boolean noFlaggedMessages = true;
        try {
            logger.info("Lookup for new emails...");
            if(!inbox.isOpen()){
                inbox.open(Folder.READ_WRITE);

            }

            Message[] messages = inbox.getMessages();

            logger.info("Processing {} messages", messages.length);
            for (int i = messages.length - 1; i >= 0; i--) {
                try {
                    processEmail(messages[i],attachmentsPath);
                    messages[i].setFlag(Flags.Flag.DELETED,deleteInboxMessages);
                    if(deleteInboxMessages){
                        logger.info("Message {} is successfully flagged to be deleted from the inbox.",this.extractMessageId(messages[i]));

                    }

                    noFlaggedMessages = false;
                } catch (Exception e) {

                    if (e instanceof MailAlreadyProcessed) {
                        logger.warn("No new mails to process");
                        break;
                    }
                    else {
                        if(e instanceof SQLException){
                            logger.error("Error in a database operation. Cause : {}", e.getMessage());
                        }
                        else {
                            logger.error("Error Processing Email: {}", e.getMessage());

                        }
                        saveFileOnError(messages[i]);
                    }
                }
            }
        } catch (MessagingException e) {
            logger.error("Error fetching emails: {}", e.getMessage());
        } finally {
            closeInbox(!noFlaggedMessages && deleteInboxMessages);
            isProcessing.set(false);
        }
    }

    private void closeInbox(boolean deleteFlaggedMessages){
        try {
           inbox.close(true);
           if(deleteFlaggedMessages){
               logger.info("Successfully deleted flagged messages.");

           }
        }catch (Exception e){
            logger.error("Error closing inbox: {}", e.getMessage());

        }
    }

    private void saveFileOnError(Message message) throws MessagingException {
        try {
            Path failedMessagesDirectoryPath = Path.of(failedMessagesPath);
            if(!failedMessagesDirectoryPath.toFile().exists()){
                failedMessagesDirectoryPath.toFile().createNewFile();
            }

            processEmail(message,failedMessagesDirectoryPath.toString());
        }catch (Exception e){
            e.printStackTrace();
            logger.error("Error creating failed messages file for message : {}",extractMessageId(message));
        }
    }

    @PreDestroy
    private void closeConnection() {
        try {
            if (inbox != null && inbox.isOpen()) {
                inbox.close(true);
                logger.info("Successfully deleted flagged messages.");

            }
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (Exception e) {
            logger.error("Error closing connection or inbox: {}", e.getMessage());
        } finally {
            inbox = null;
            store = null;
        }
    }

    private void processEmail(Message message,String basePath) throws Exception {
        String status = "STARTED";
        String messageId = "";
        List<String> emailAttachmentPaths = new ArrayList<>();

        try {
            messageId = extractMessageId(message);

            List<IMail> existingMails = iMailRepository.findByMessageId(messageId);
            if (!existingMails.isEmpty()) {
                throw new MailAlreadyProcessed("Mail already processed found in database by messageId");
            }

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
            if (message.isMimeType("text/plain")) {
                if(emailBody.isEmpty()){
                    emailBody.append(message.getContent().toString());
                }
            } else if (message.isMimeType("multipart/*")) {
               emailAttachmentPaths =  processMultipart((Multipart) message.getContent(), emailBody,basePath);
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
            iMail.setSubject(message.getSubject() != null ? message.getSubject() : "[No Subject]");
            iMail.setReceivedAt(LocalDateTime.ofInstant(message.getSentDate().toInstant(), ZoneId.systemDefault()));

            try {
                iMailRepository.save(iMail);
            } catch (Exception e) {
                throw new SQLException("Cannot save email body for messageID : " + messageId);
            }

            for (int i = 0; i < emailAttachmentPaths.size(); i++) {

                iMail = new IMail();
                iMail.setSender(from);
                iMail.setMessageId(messageId);
                iMail.setBody("");
                iMail.setAttachmentsPath(emailAttachmentPaths.get(i));
                iMail.setSubject(message.getSubject() != null ? message.getSubject() : "[No Subject]");
                iMail.setReceivedAt(LocalDateTime.ofInstant(message.getSentDate().toInstant(), ZoneId.systemDefault()));
                try {
                    iMailRepository.save(iMail);
                } catch (Exception e) {
                    throw new SQLException("Cannot save email attachment file path : " + emailAttachmentPaths.get(i) +  " for messageID : " + messageId);
                }
            }
            status = "COMPLETED";

        } catch (Exception e) {
            if(e instanceof  SQLException){
                throw new SQLException(e.getMessage());
            }
            throw e;
        } finally {
            logStatus(status, messageId);
        }
    }

    private void logStatus(String status, String messageId) {
        String icon = "🔄"; // Default processing icon
        if (status.startsWith("COMPLETED")) {
            icon = "✅";
        } else if (!status.startsWith("PROCESSING")) {
            return;
        }

        // Show first 4 chars of message ID for security
        logger.info("{} | {} | ID: {}", icon, status, messageId);
    }

    private List<String> processMultipart(Multipart multipart, StringBuilder emailBody,String basePath) throws MessagingException, IOException {
        List<String> emailAttachmentsPaths = new ArrayList<>();
        String numMessage = extractNumMessage(emailBody.toString());
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            if (bodyPart.isMimeType("text/plain")) {
                if(emailBody.isEmpty()){
                    emailBody.append(bodyPart.getContent().toString());
                    if(numMessage.isEmpty() ){
                            numMessage = extractNumMessage(emailBody.toString());
                    }

                }
            } else if (bodyPart.isMimeType("text/html")) {


                emailAttachmentsPaths.add(saveHtmlContent(numMessage,bodyPart,basePath));
            } else if (Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition()) || bodyPart.getFileName() != null) {
                emailAttachmentsPaths.add(saveAttachment(bodyPart.getFileName()  , numMessage, bodyPart,basePath));
            }
        }
        return emailAttachmentsPaths;
    }


    private String saveHtmlContent(String fileNamePrefix,BodyPart bodyPart,String basePath) throws MessagingException, IOException {
        String fileName = fileNamePrefix + "_" + UUID.randomUUID() + ".html";

       return   saveFileWithBodyPart(fileName,bodyPart,basePath);
    }

    private String saveAttachment(String fileName,String fileNamePrefix,BodyPart bodyPart,String basePath) throws MessagingException, IOException {

         fileName = fileNamePrefix + "_" + fileName;

       return saveFileWithBodyPart(fileName,bodyPart,basePath);
    }

    private String saveFileWithBodyPart(String fileName, BodyPart bodyPart,String basePath) throws MessagingException, IOException {
        Matcher matcher = pattern.matcher(fileName);

        if(matcher.find()){
            fileName = UUID.randomUUID().toString();
        }
        Path messagesFolderPath =  Path.of(basePath);
        Path filePath = messagesFolderPath.resolve(fileName);
        Files.write(filePath, bodyPart.getInputStream().readAllBytes());
        return filePath.toString();
    }


    private String extractMessageId(Message message) throws MessagingException {
        String messageId = message.getHeader("Message-ID")[0];
        return messageId != null ? messageId.replaceAll("[^A-Za-z0-9]", "") : "";
    }

    private String extractNumMessage(String messageContent) {
        Matcher matcher = NUMBER_MESSAGE_PATTERN.matcher(messageContent);
        return matcher.find() ? matcher.group(1) : "";
    }
}
