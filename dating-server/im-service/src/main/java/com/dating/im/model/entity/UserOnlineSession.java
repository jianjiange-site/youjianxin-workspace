package com.dating.im.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 用户在线会话:上线开一行({@code offlineAt = NULL}),下线回填 {@code offlineAt} + {@code durationSeconds}。
 *
 * <p>时间一律 UTC({@code TIMESTAMPTZ})。表由 Hibernate {@code ddl-auto=update} 自动建,列名由 Spring 的
 * camelCase→snake_case 命名策略映射(如 {@code userId}→{@code user_id})。
 */
@Entity
@Table(name = "user_online_session")
public class UserOnlineSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 开 session 那个设备的 platformID(信息性;简单版忽略多设备)。 */
    private String platform;

    @Column(nullable = false)
    private OffsetDateTime onlineAt;

    private OffsetDateTime offlineAt;

    /** 本次在线时长(秒),下线时回填。 */
    private Long durationSeconds;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Column(nullable = false)
    private Boolean deleted = false;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (deleted == null) {
            deleted = false;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UserOnlineSession() {}

    public UserOnlineSession(Long userId, String platform, OffsetDateTime onlineAt) {
        this.userId = userId;
        this.platform = platform;
        this.onlineAt = onlineAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public OffsetDateTime getOnlineAt() { return onlineAt; }
    public void setOnlineAt(OffsetDateTime onlineAt) { this.onlineAt = onlineAt; }

    public OffsetDateTime getOfflineAt() { return offlineAt; }
    public void setOfflineAt(OffsetDateTime offlineAt) { this.offlineAt = offlineAt; }

    public Long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Long durationSeconds) { this.durationSeconds = durationSeconds; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
}
