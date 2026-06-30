package com.cu.collective_unconscious.domain.entities;

import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.domain.entities.enums.WritingMode;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Table(name = "works")
public class Work {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String preface;

  @Column
  private boolean completed = false;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private WorkType workType;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private TurnStyle turnStyle;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private WritingMode writingMode;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  @Column(nullable = false)
  private LocalDateTime updatedAt;

  @Column(nullable = true)
  private String accessCode;

  @Version
  private Long version;

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Work work)) return false;
    return id != null && id.equals(work.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = LocalDateTime.now();
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }

}
