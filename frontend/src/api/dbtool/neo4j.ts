import request from '@/utils/request'

// ============ Neo4j 图数据库工作台 ============

// 枚举连接可见的数据库
export function neo4jDatabases(connId) {
  return request({
    url: '/dbtool/neo4j/databases',
    method: 'get',
    params: { connId }
  })
}

// 获取 Schema（标签 / 关系类型 / 属性键）
export function neo4jSchema(connId, database) {
  return request({
    url: '/dbtool/neo4j/schema',
    method: 'get',
    params: { connId, database }
  })
}

// 执行 Cypher，返回图/表 DTO
export function neo4jQuery(data) {
  return request({
    url: '/dbtool/neo4j/query',
    method: 'post',
    data
  })
}

// 生成执行计划（EXPLAIN 不实际执行）
export function neo4jExplain(data) {
  return request({
    url: '/dbtool/neo4j/explain',
    method: 'post',
    data
  })
}

// 按节点展开一层邻居
export function neo4jExpand(data) {
  return request({
    url: '/dbtool/neo4j/expand',
    method: 'post',
    data
  })
}

// 取消查询
export function neo4jCancel(executionId) {
  return request({
    url: `/dbtool/neo4j/query/${executionId}/cancel`,
    method: 'post'
  })
}
