package org.yan.lightofshadowbackend.shop.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.yan.lightofshadowbackend.shop.entity.UserAddressEntity;

@Mapper
public interface UserAddressMapper {

    int insert(UserAddressEntity address);

    List<UserAddressEntity> selectByUserId(@Param("userId") String userId);

    UserAddressEntity selectByIdAndUserId(@Param("id") String id, @Param("userId") String userId);

    int updateById(UserAddressEntity address);

    int deleteByIdAndUserId(@Param("id") String id, @Param("userId") String userId);

    int clearDefaultByUserId(@Param("userId") String userId, @Param("now") Instant now);
}
