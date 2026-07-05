package com.cu.collective_unconscious.domain.entities.enums;

/**
 * Controls discovery of a work (search / recommend / listing), see design doc §2.2.
 * Orthogonal to {@link WritingMode} and to the invitation code (which controls joining).
 */
public enum Visibility {
  PUBLIC,
  PRIVATE
}
