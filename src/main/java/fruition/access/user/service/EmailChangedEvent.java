package fruition.access.user.service;

import java.time.Instant;

/** 이메일 변경이 커밋되면 기존 이메일로 알림을 보내기 위한 이벤트. */
public record EmailChangedEvent(String userId, String previousEmail, String newEmail, Instant changedAt) {}
