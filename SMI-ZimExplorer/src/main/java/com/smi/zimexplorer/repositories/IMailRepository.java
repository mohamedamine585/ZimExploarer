package com.smi.zimexplorer.repositories;

import com.smi.zimexplorer.entities.IMail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IMailRepository extends JpaRepository<IMail,Long> {

    public List<IMail> findByMessageId(String messageId);
}
