package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.service.IDbExportService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.domain.R;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 大结果导出 控制器(P2)
 * <p>POST /download 返回二进制流(前端 fetch blob 下载), 避免长 SQL 进入 URL。</p>
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/export")
public class DbExportController {

    private final IDbExportService exportService;

    public DbExportController(IDbExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * 导出预览: 列信息 + 估算行数
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/preview")
    public R<Map<String, Object>> preview(@RequestBody Map<String, Object> body) {
        Object connIdRaw = body == null ? null : body.get("connId");
        Object sqlRaw = body == null ? null : body.get("sql");
        if (connIdRaw == null || sqlRaw == null) {
            return R.fail("connId 与 sql 不能为空");
        }
        Long connId = Long.valueOf(String.valueOf(connIdRaw));
        String sql = String.valueOf(sqlRaw);
        if (StrUtil.isBlank(sql)) {
            return R.fail("connId 与 sql 不能为空");
        }
        return R.ok(exportService.preview(connId, sql));
    }

    /**
     * 流式导出下载(CSV/JSON/SQL 文本格式 + Excel xlsx 二进制格式)
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(@RequestBody Map<String, Object> body) {
        Object connIdRaw = body == null ? null : body.get("connId");
        Object sqlRaw = body == null ? null : body.get("sql");
        if (connIdRaw == null || sqlRaw == null) {
            return ResponseEntity.badRequest().build();
        }
        Long connId = Long.valueOf(String.valueOf(connIdRaw));
        String sql = String.valueOf(sqlRaw);
        String format = body == null || body.get("format") == null ? null : String.valueOf(body.get("format"));
        long maxRows = 100000L;
        Object maxRowsRaw = body == null ? null : body.get("maxRows");
        if (maxRowsRaw != null) {
            try {
                maxRows = Long.parseLong(String.valueOf(maxRowsRaw));
            } catch (NumberFormatException ignored) {
                // 使用默认值
            }
        }
        String finalFormat = StrUtil.blankToDefault(format, "csv");
        // maxRows 可能被上面 try 二次赋值, 复制为 final 变量供 lambda 安全引用
        final long effectiveMaxRows = maxRows;
        if (connId == null || StrUtil.isBlank(sql)) {
            return ResponseEntity.badRequest().build();
        }
        boolean isExcel = "xlsx".equalsIgnoreCase(finalFormat) || "excel".equalsIgnoreCase(finalFormat);
        String ext = isExcel ? "xlsx" : "json".equals(finalFormat) ? "json" : "sql".equals(finalFormat) ? "sql" : "csv";
        String fileName = "export_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "." + ext;
        MediaType contentType = isExcel
            ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            : MediaType.APPLICATION_OCTET_STREAM;
        StreamingResponseBody stream = outputStream -> {
            if (isExcel) {
                exportService.exportToExcel(connId, sql, effectiveMaxRows, outputStream);
            } else {
                Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
                if ("csv".equals(finalFormat)) {
                    writer.write("\uFEFF");
                }
                exportService.exportTo(connId, sql, finalFormat, effectiveMaxRows, writer);
                writer.flush();
            }
        };
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
            .contentType(contentType)
            .body(stream);
    }

}
