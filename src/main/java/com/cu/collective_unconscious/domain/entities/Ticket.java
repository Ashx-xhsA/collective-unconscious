package com.cu.collective_unconscious.domain.entities;

import java.time.LocalDateTime;
import java.util.UUID;

import com.cu.collective_unconscious.domain.entities.enums.TicketStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Writing-permission ticket (see design doc §2.5). Each work has an independent
 * ticket queue partitioned by work_id. Tickets are never deleted, only re-statused.
 */
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Table(
    name = "tickets",
    uniqueConstraints = @UniqueConstraint(columnNames = {"work_id", "ticket_number"}))
public class Ticket {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "work_id", nullable = false)
  private Work work;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "holder_id", nullable = false)
  private User holder;

  // Total order within the work.
  @Column(name = "ticket_number", nullable = false)
  private long ticketNumber;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  @Builder.Default
  private TicketStatus status = TicketStatus.WAITING;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  // Only set once the ticket becomes ACTIVE; TTL is only consumed after activation.
  @Column(nullable = true)
  private LocalDateTime activatedAt;

  // activatedAt + TTL.
  @Column(nullable = true)
  private LocalDateTime expiresAt;

  // Points to the submitted contribution once CONSUMED.
  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "contribution_id")
  private Contribution contribution;

  // Optimistic lock for the fencing / concurrency story (§2.5, appendix B).
  @Version
  private Long version;

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Ticket ticket)) return false;
    return id != null && id.equals(ticket.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
