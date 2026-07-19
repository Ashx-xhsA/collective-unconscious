package com.cu.collective_unconscious.dtos;

import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.domain.entities.enums.WritingMode;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建作品的请求体,对应设计文档 §2.2。
 * 只包含题主创建时能决定的字段;createdAt / completed / owner 等由后端填充。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CreateWorkRequestDto {

  // --- 描述属性(§2.2)---

  // 题目,必填。
  @NotBlank(message = "Title is required")
  @Size(max = 255, message = "Title must be at most 255 characters")
  private String title;

  // 详情/引子,必填。
  @NotBlank(message = "Preface is required")
  private String preface;

  // 标签,选填。传标签名,后端负责查找或新建对应 Tag。
  private Set<String> tagNames;

  // --- 关键属性(创建时确定,不可修改,§2.2)---

  @NotNull(message = "Work type is required")
  private WorkType workType;

  @NotNull(message = "Turn style is required")
  private TurnStyle turnStyle;

  @NotNull(message = "Writing mode is required")
  private WritingMode writingMode;

  // --- 可见性与加入策略(§2.2)---

  // 缺省时后端按实体默认值 PUBLIC 处理。
  private Visibility visibility;

  // nullable:有则领令牌须输入,无则任何登录用户可直接领。
  private String accessCode;

  // --- 限制属性(§2.2)。null = 无限/用实体默认值。---

  // 最大轮数;null = 无限。
  @Min(value = 1, message = "Max rounds must be at least 1")
  private Integer maxRounds;

  // 每份贡献最大字数;null = 用默认值 1000。
  @Min(value = 1, message = "Max words must be at least 1")
  private Integer maxWords;

  // 每份贡献最大段落数;null = 用默认值 1。
  @Min(value = 1, message = "Max paragraphs must be at least 1")
  private Integer maxParagraphs;

  // 令牌 TTL(秒);null = 用默认值 3600。
  @Min(value = 1, message = "Ticket TTL must be at least 1 second")
  private Long ticketTtlSeconds;

  // 参与人数上限;null = 无限。
  @Min(value = 1, message = "Max participants must be at least 1")
  private Integer maxParticipants;
}
