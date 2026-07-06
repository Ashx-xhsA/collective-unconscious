package com.cu.collective_unconscious.domain.entities;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Table(
    name = "contributions",
    uniqueConstraints = @UniqueConstraint(columnNames = {"work_id", "sequence_number"}))
public class Contribution {

  @Id
  @GeneratedValue(strategy= GenerationType.UUID)
  private UUID id;

  // The work this contribution belongs to.
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "work_id", nullable = false)
  private Work work;

  // Who wrote it.
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "author_id", nullable = false)
  private User author;

  // Nullable: Novel chapters carry a title; Article paragraphs leave it null (§2.4).
  @Column(nullable = true)
  private String title;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  // Per-work, append-only ordering. Unique with work_id (§2.4).
  @Column(name = "sequence_number", nullable = false)
  private int sequenceNumber;

  // Logical delete: the row and its sequence_number are retained (§2.4, §7.1).
  @Column(nullable = false)
  private boolean deleted = false;

  // Who performed the delete; the 题主/贡献者/管理员 label is derived from this
  // user's relationship to the work at render time. Null unless deleted.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "deleted_by_id")
  private User deletedBy;

  @Column(nullable = false)
  private boolean draft = true;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Contribution that)) return false;
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
