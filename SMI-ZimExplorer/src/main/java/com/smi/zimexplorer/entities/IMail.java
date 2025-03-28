package com.smi.zimexplorer.entities;


import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "IMAIL")
public class IMail {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE,generator = "imail_seq")
    @SequenceGenerator(name = "imail_seq",sequenceName = "imail_seq",allocationSize = 1)
    @Column(name = "ID_IMAIL", nullable = false)
    private Long id;

    @Size(max = 2048)
    @NotNull
    @Column(name = "SENDER", nullable = false, length = 512)
    private String sender;


    @Lob
    private String body;

    @Size(max = 1024)
    @Column(name = "SUBJECT", length = 1024)
    private String subject;

    @Column(name = "message_Id",unique = true)
    private String messageId;

    private String attachmentsPath;


    @NotNull
    @Column(name = "RECEIVED_AT", nullable = false)
    private LocalDateTime receivedAt;

}