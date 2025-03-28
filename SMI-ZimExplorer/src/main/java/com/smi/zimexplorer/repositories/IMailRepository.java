package com.smi.zimexplorer.repositories;

import com.smi.zimexplorer.entities.IMail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IMailRepository extends JpaRepository<IMail,Long> {

    public IMail findByMessageId(String messageId);
}
