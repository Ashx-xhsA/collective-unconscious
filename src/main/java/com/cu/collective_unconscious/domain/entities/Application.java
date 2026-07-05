package com.cu.collective_unconscious.domain.entities;

import java.time.LocalDateTime;
import java.util.UUID;

import com.cu.collective_unconscious.domain.entities.enums.ApplicationStatus;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A request to participate in a work (see design doc §2.3, §4.3).
 */
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Table(name = "applications")
public class Application {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "work_id", nullable = false)
  private Work work;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  @Builder.Default
  private ApplicationStatus status = ApplicationStatus.PENDING;

  // Optional note the applicant sends to the owner along with the request.
  @Column(nullable = true, columnDefinition = "TEXT")
  private String message;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  // Set when the owner approves/rejects.
  @Column(nullable = true)
  private LocalDateTime reviewedAt;

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Application that)) return false;
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
