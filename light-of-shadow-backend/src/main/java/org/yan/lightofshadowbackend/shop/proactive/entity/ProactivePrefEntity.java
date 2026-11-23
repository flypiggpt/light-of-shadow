package org.yan.lightofshadowbackend.shop.proactive.entity;

import java.time.Instant;

/** 用户提醒偏好：type 为 ALL（总开关）或具体信号类型；无记录视为开启。 */
public class ProactivePrefEntity {

    private String userId;
    private String type;
    private Integer enabled;
    private Instant updatedAt;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
