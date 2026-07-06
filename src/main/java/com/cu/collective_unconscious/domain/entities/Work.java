package com.cu.collective_unconscious.domain.entities;

import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.domain.entities.enums.WritingMode;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
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

  // --- Key attributes (immutable after creation, §2.2) ---

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private WorkType workType;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private TurnStyle turnStyle;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private WritingMode writingMode;

  // --- Discovery / joining (§2.2) ---

  // Controls discovery (search / recommend / listing).
  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  @Builder.Default
  private Visibility visibility = Visibility.PUBLIC;

  // Maps to the design doc's invitation_code: controls joining. Null => any
  // logged-in user may draw a ticket directly.
  @Column(nullable = true)
  private String accessCode;

  // --- Limit attributes (§2.2). Null = unlimited. ---

  // Max rounds; null = unlimited.
  @Column(nullable = true)
  private Integer maxRounds;

  @Column(nullable = false)
  @Builder.Default
  private int maxWords = 1000;

  @Column(nullable = false)
  @Builder.Default
  private int maxParagraphs = 1;

  // Ticket TTL in seconds.
  @Column(nullable = false)
  @Builder.Default
  private long ticketTtlSeconds = 3600;

  // Max participants; null = unlimited.
  @Column(nullable = true)
  private Integer maxParticipants;

  // --- Participation relationships (§2.3) ---

  // 题主: owner of the work.
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "created_author_id", nullable = false)
  private User createdAuthor;

  // Contributors: a user joins only after successfully submitting at least one
  // contribution. Ordering is not expressed here (it lives in tickets).
  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "work_authors",
      joinColumns = @JoinColumn(name = "work_id"),
      inverseJoinColumns = @JoinColumn(name = "user_id"))
  @Builder.Default
  private Set<User> authors = new HashSet<>();

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "work_tags",
      joinColumns = @JoinColumn(name = "work_id"),
      inverseJoinColumns = @JoinColumn(name = "tag_id"))
  @Builder.Default
  private Set<Tag> tags = new HashSet<>();

  @Column(nullable = false)
  private LocalDateTime createdAt;

  @Column(nullable = false)
  private LocalDateTime updatedAt;

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
