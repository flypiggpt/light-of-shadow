package org.yan.lightofshadowbackend.shoppingguide.entity;

import java.time.Instant;

public class SessionEntity {

    private String id;
    private String userId;
    private String entry;
    private String source;
    private String locale;
    private String status;
    private String welcomeMessage;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getEntry() { return entry; }
    public void setEntry(String entry) { this.entry = entry; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getWelcomeMessage() { return welcomeMessage; }
    public void setWelcomeMessage(String welcomeMessage) { this.welcomeMessage = welcomeMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    private String summary;
    private String lastIntent;
    private String recommendationsJson;

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getLastIntent() { return lastIntent; }
    public void setLastIntent(String lastIntent) { this.lastIntent = lastIntent; }
    public String getRecommendationsJson() { return recommendationsJson; }
    public void setRecommendationsJson(String recommendationsJson) { this.recommendationsJson = recommendationsJson; }

}
