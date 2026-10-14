package pan.dromara.dbtool.mapper;

import pan.dromara.dbtool.domain.DbConn;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 数据库连接配置 数据层
 *
 * @author doubao
 */
@Mapper
public interface DbConnMapper extends BaseMapperPlus<DbConn, DbConn> {
}
