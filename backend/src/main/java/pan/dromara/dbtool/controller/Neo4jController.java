package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.domain.bo.Neo4jExpandBo;
import pan.dromara.dbtool.domain.bo.Neo4jQueryBo;
import pan.dromara.dbtool.domain.vo.Neo4jGraphVo;
import pan.dromara.dbtool.domain.vo.Neo4jResultVo;
import pan.dromara.dbtool.service.Neo4jService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Neo4j 图数据库工作台 控制器
 * <p>覆盖 Cypher 查询、数据库枚举、Schema 浏览、邻居展开与查询取消。</p>
 *
 * @author doubao
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dbtool/neo4j")
public class Neo4jController extends BaseController {

    private final Neo4jService neo4jService;

    /**
     * 枚举连接可见的数据库
     */
    @SaCheckPermission("dbtool:conn:list")
    @GetMapping("/databases")
    public R<List<String>> databases(@RequestParam Long connId) {
        return R.ok(neo4jService.databases(connId));
    }

    /**
     * 获取 Schema 信息（标签 / 关系类型 / 属性键）
     */
    @SaCheckPermission("dbtool:conn:list")
    @GetMapping("/schema")
    public R<Map<String, Object>> schema(@RequestParam Long connId,
                                         @RequestParam(required = false) String database) {
        return R.ok(neo4jService.schema(connId, database));
    }

    /**
     * 执行 Cypher，返回图/表 DTO
     */
    @SaCheckPermission("dbtool:conn:query")
    @PostMapping("/query")
    public R<Neo4jResultVo> query(@Validated @RequestBody Neo4jQueryBo bo) {
        return R.ok(neo4jService.query(bo));
    }

    /**
     * 生成执行计划（EXPLAIN 不实际执行查询，无副作用）
     */
    @SaCheckPermission("dbtool:conn:query")
    @PostMapping("/explain")
    public R<Neo4jResultVo> explain(@Validated @RequestBody Neo4jQueryBo bo) {
        return R.ok(neo4jService.explain(bo));
    }

    /**
     * 按节点展开一层邻居（显式用户操作）
     */
    @SaCheckPermission("dbtool:conn:query")
    @PostMapping("/expand")
    public R<Neo4jGraphVo> expand(@Validated @RequestBody Neo4jExpandBo bo) {
        return R.ok(neo4jService.expand(bo));
    }

    /**
     * 取消当前用户拥有的查询任务
     */
    @SaCheckPermission("dbtool:conn:query")
    @PostMapping("/query/{executionId}/cancel")
    public R<Boolean> cancel(@PathVariable String executionId) {
        return R.ok(neo4jService.cancel(executionId));
    }
}
