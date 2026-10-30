package io.github.ajax1.campusradar.mail;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MailSeenRepository extends JpaRepository<MailSeen, String> {

    List<MailSeen> findTop50ByTypeNotOrderByReceivedAtDesc(MailFinding.Type type);
}
