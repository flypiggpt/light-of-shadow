package org.yan.lightofshadowbackend.shoppingguide.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.yan.lightofshadowbackend.shoppingguide.entity.MessageEntity;
import org.yan.lightofshadowbackend.shoppingguide.entity.ShoppingGuideFeedbackEntity;
import org.yan.lightofshadowbackend.shoppingguide.entity.SessionEntity;

public interface ShoppingGuideMapper {

    int insertSession(SessionEntity session);

    SessionEntity selectSessionById(@Param("id") String id);

    SessionEntity selectLatestSessionByUserId(@Param("userId") String userId);

    int updateSessionStatus(@Param("id") String id, @Param("status") String status, @Param("now") Instant now);

    int insertMessage(MessageEntity message);

    int updateSessionExtra(@Param("id") String id, @Param("summary") String summary,
                           @Param("lastIntent") String lastIntent,
                           @Param("recommendationsJson") String recommendationsJson,
                           @Param("now") java.time.Instant now);

    int insertFeedback(ShoppingGuideFeedbackEntity feedback);

    List<MessageEntity> selectMessagesBySession(@Param("sessionId") String sessionId);

    int countMessagesBySession(@Param("sessionId") String sessionId);
}
