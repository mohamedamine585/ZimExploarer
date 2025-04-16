package com.smi.zimexplorer.exceptions;


import lombok.AllArgsConstructor;
import lombok.Data;

@Data
public class MailAlreadyProcessed extends Exception{
    private String message;
    public MailAlreadyProcessed(String message){
        super(message);
    }
}
