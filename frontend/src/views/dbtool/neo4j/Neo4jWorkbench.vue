<template>
  <div class="neo4j-workbench">
    <!-- 顶部：连接 / 数据库 / 访问模式 -->
    <div class="n4-header">
      <span class="n4-title">Neo4j 图数据库</span>
      <span class="n4-conn">{{ connName }}</span>
      <el-select v-model="database" size="small" placeholder="选择数据库" class="n4-db" clearable :loading="loadingDbs">
        <el-option v-for="d in databases" :key="d" :label="d" :value="d" />
      </el-select>
      <el-tag size="small" :type="readOnly ? 'warning' : 'success'" class="n4-mode">
        {{ readOnly ? '只读' : '读写' }}
      </el-tag>
      <span class="n4-status" v-if="serverStatus">{{ serverStatus }}</span>
      <div class="n4-header-right">
        <el-button size="small" @click="loadSchema" :loading="loadingSchema">刷新 Schema</el-button>
        <el-button size="small" icon="Refresh" @click="loadDatabases" :loading="loadingDbs">刷新库</el-button>
      </div>
    </div>

    <!-- Cypher 编辑器 + 工具栏 -->
    <div class="n4-editor-area">
      <div ref="editorEl" class="n4-editor"></div>
      <div class="n4-toolbar">
        <el-button type="primary" size="small" icon="CaretRight" :loading="running" @click="runCypher">执行</el-button>
        <el-button size="small" icon="CloseBold" :disabled="!running" @click="cancelQuery">取消</el-button>
        <el-button size="small" icon="Document" @click="runExplain" title="生成执行计划（不实际执行）">执行计划</el-button>
        <el-button size="small" icon="MagicStick" @click="formatCypher">格式化</el-button>
        <el-button size="small" icon="Delete" @click="clearCypher">清空</el-button>
        <div class="n4-toolbar-sep"></div>
        <el-checkbox v-model="showParams" size="small">参数</el-checkbox>
        <el-checkbox v-model="writeMode" size="small" :disabled="readOnly" :title="readOnly ? '只读连接不可写' : '写入 Cypher 需确认'">写操作</el-checkbox>
        <el-input-number v-model="resultLimit" size="small" :min="50" :max="5000" :step="100" controls-position="right" style="width:110px" title="结果行数上限" />
      </div>
    </div>

    <!-- 参数面板 -->
    <div v-if="showParams" class="n4-params">
      <div class="n4-params-label">查询参数 (JSON 对象，如 {"name": "Alice"})</div>
      <el-input v-model="paramsText" type="textarea" :rows="2" placeholder='{"name": "Alice"}' spellcheck="false" />
      <div v-if="paramsError" class="n4-params-error">{{ paramsError }}</div>
    </div>

    <!-- 错误提示 -->
    <el-alert v-if="errorMsg" :title="errorMsg" type="error" :closable="false" show-icon class="n4-error" />

    <!-- 结果区 -->
    <div class="n4-result">
      <div v-if="!hasResult" class="n4-empty">
        <el-empty description="执行 Cypher 后在这里查看图 / 表格 / 摘要结果" :image-size="90" />
      </div>
      <el-tabs v-else v-model="activeView" class="n4-tabs">
        <el-tab-pane label="图视图" name="graph">
          <div class="n4-graph-layout">
            <div ref="graphEl" class="n4-graph"></div>
            <!-- 节点/关系属性侧栏 -->
            <div v-if="inspected" class="n4-inspector">
              <div class="n4-insp-head">
                <span class="n4-insp-title">{{ inspectedType === 'node' ? '节点详情' : '关系详情' }}</span>
                <el-button link type="primary" size="small" @click="fitGraph">聚焦</el-button>
                <el-button link type="danger" size="small" @click="inspected = null">×</el-button>
              </div>
              <template v-if="inspectedType === 'node'">
                <div class="n4-insp-row"><span class="n4-insp-k">标签</span><span class="n4-insp-v">{{ (inspected.labels || []).join(', ') || '—' }}</span></div>
                <div class="n4-insp-row"><span class="n4-insp-k">属性</span>
                  <div class="n4-insp-props" v-if="Object.keys(inspected.properties || {}).length">
                    <div v-for="(pv, pk) in inspected.properties" :key="pk" class="n4-prop-row">
                      <span class="n4-prop-k">{{ pk }}</span>
                      <span class="n4-prop-v">{{ fmtValue(pv) }}</span>
                    </div>
                  </div>
                  <span v-else class="n4-insp-muted">无属性</span>
                </div>
                <div class="n4-insp-row">
                  <el-button size="small" type="primary" plain :loading="expanding" @click="expandNode">展开邻居</el-button>
                </div>
              </template>
              <template v-else>
                <div class="n4-insp-row"><span class="n4-insp-k">类型</span><span class="n4-insp-v">{{ inspected.type }}</span></div>
                <div class="n4-insp-row"><span class="n4-insp-k">起点 → 终点</span><span class="n4-insp-v">{{ relEndpointLabel(inspected.source) }} → {{ relEndpointLabel(inspected.target) }}</span></div>
                <div class="n4-insp-row"><span class="n4-insp-k">属性</span>
                  <div class="n4-insp-props" v-if="Object.keys(inspected.properties || {}).length">
                    <div v-for="(pv, pk) in inspected.properties" :key="pk" class="n4-prop-row">
                      <span class="n4-prop-k">{{ pk }}</span>
                      <span class="n4-prop-v">{{ fmtValue(pv) }}</span>
                    </div>
                  </div>
                  <span v-else class="n4-insp-muted">无属性</span>
                </div>
              </template>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane :label="'表格 (' + (rows.length) + ')'" name="table">
          <el-table :data="rows" size="small" border stripe height="100%" class="n4-table">
            <el-table-column v-for="c in columns" :key="c" :prop="c" :label="c" min-width="140">
              <template #default="{ row }">
                <span v-if="row[c] && (row[c].type === 'node' || row[c].type === 'rel')" class="n4-cell-graph" @click="focusGraphCell(row[c])">
                  {{ row[c].type === 'node' ? '⛁ ' + cellDisplay(row[c]) : '→ ' + cellDisplay(row[c]) }}
                </span>
                <span v-else>{{ fmtCell(row[c]) }}</span>
              </template>
            </el-table-column>
            <template #empty><el-empty description="无返回数据" :image-size="60" /></template>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="摘要" name="summary">
          <div class="n4-summary">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="返回列">{{ columns.join(', ') || '—' }}</el-descriptions-item>
              <el-descriptions-item label="行数">{{ rows.length }}</el-descriptions-item>
              <el-descriptions-item label="图节点">{{ graph ? graph.nodes.length : 0 }}</el-descriptions-item>
              <el-descriptions-item label="图关系">{{ graph ? graph.rels.length : 0 }}</el-descriptions-item>
              <el-descriptions-item label="耗时">{{ resultAvailableAfterMs }} ms</el-descriptions-item>
              <el-descriptions-item label="是否截断">{{ truncated ? '是（结果已达上限）' : '否' }}</el-descriptions-item>
              <el-descriptions-item v-if="hasCounters" label="影响计数" :span="2">{{ countersText }}</el-descriptions-item>
            </el-descriptions>
          </div>
        </el-tab-pane>
        <el-tab-pane label="Schema" name="schema">
          <div class="n4-schema">
            <div class="n4-schema-note" v-if="schemaNote">{{ schemaNote }}</div>
            <div class="n4-schema-col">
              <div class="n4-schema-title">节点标签 ({{ schemaLabels.length }})</div>
              <div v-if="schemaLabelStats.length" class="n4-schema-list">
                <div v-for="s in schemaLabelStats" :key="'l' + s.name" class="n4-schema-item" @click="insertLabel(s.name)">
                  <span class="n4-schema-name">:{{ s.name }}</span>
                  <span class="n4-schema-count">{{ s.count }}</span>
                </div>
              </div>
              <div v-else class="n4-schema-muted">无标签</div>
            </div>
            <div class="n4-schema-col">
              <div class="n4-schema-title">关系类型 ({{ schemaRelTypes.length }})</div>
              <div v-if="schemaRelStats.length" class="n4-schema-list">
                <div v-for="s in schemaRelStats" :key="'r' + s.name" class="n4-schema-item" @click="insertLabel(s.name)">
                  <span class="n4-schema-name">[:{{ s.name }}]</span>
                  <span class="n4-schema-count">{{ s.count }}</span>
                </div>
              </div>
              <div v-else class="n4-schema-muted">无关系类型</div>
            </div>
            <div class="n4-schema-col">
              <div class="n4-schema-title">属性键 ({{ schemaPropKeys.length }})</div>
              <div v-if="schemaPropKeys.length" class="n4-schema-list">
                <div v-for="k in schemaPropKeys" :key="'p' + k" class="n4-schema-item" @click="insertLabel(k)">
                  <span class="n4-schema-name">{{ k }}</span>
                </div>
              </div>
              <div v-else class="n4-schema-muted">无属性键</div>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="最近查询" name="history">
          <div class="n4-history">
            <div class="n4-history-toolbar">
              <span class="n4-history-title">该连接的 Cypher 执行历史（点击回填）</span>
              <el-button size="small" link type="primary" @click="loadHistory" :loading="loadingHistory">刷新</el-button>
            </div>
            <div v-if="historyList.length" class="n4-history-list">
              <div v-for="h in historyList" :key="h.id" class="n4-history-item">
                <div class="n4-history-main" @click="useHistory(h)">
                  <div class="n4-history-sql">{{ h.sqlText }}</div>
                  <div class="n4-history-meta">
                    <el-tag size="small" :type="h.status === 'SUCCESS' ? 'success' : (h.status === 'CANCELLED' ? 'info' : 'danger')">{{ h.status }}</el-tag>
                    <span class="n4-history-rows">{{ h.rowsAffected }} 行</span>
                    <span class="n4-history-time">{{ h.durationMs }} ms</span>
                    <span class="n4-history-date">{{ fmtTime(h.executedAt) }}</span>
                    <span v-if="h.errorMsg" class="n4-history-err">{{ h.errorMsg }}</span>
                  </div>
                </div>
                <el-button size="small" link type="danger" @click.stop="removeHistory(h)">删除</el-button>
              </div>
            </div>
            <el-empty v-else description="暂无查询历史" :image-size="60" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup name="Neo4jWorkbench">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as monaco from 'monaco-editor'
import cytoscape from 'cytoscape'
import { ElMessage, ElMessageBox } from 'element-plus'
import { neo4jDatabases, neo4jSchema, neo4jQuery, neo4jExpand, neo4jCancel, neo4jExplain } from '@/api/dbtool/neo4j'
import { recordSqlHistory, listSqlHistory, delSqlHistory } from '@/api/dbtool/index'

const props = defineProps({
  connId: { type: [Number, String], required: true },
  connName: { type: String, default: '' },
  readOnly: { type: Boolean, default: false }
})

// ============ 状态 ============
const databases = ref([])
const database = ref('')
const loadingDbs = ref(false)
const serverStatus = ref('')
const showParams = ref(false)
const paramsText = ref('')
const paramsError = ref('')
const writeMode = ref(false)
const resultLimit = ref(500)
const running = ref(false)
const loadingSchema = ref(false)
const expanding = ref(false)
const currentExecutionId = ref('')
const errorMsg = ref('')
const activeView = ref('graph')

const columns = ref([])
const rows = ref([])
const graph = ref({ nodes: [], rels: [] })
const resultAvailableAfterMs = ref(0)
const truncated = ref(false)
const counters = ref({})
const hasCounters = computed(() => Object.keys(counters.value).length > 0)
const countersText = computed(() => Object.entries(counters.value).map(([k, v]) => `${k}: ${v}`).join('；'))

// 最近查询历史（复用 dbtool_sql_history 表，兼作写操作审计）
const historyList = ref([])
const loadingHistory = ref(false)
const hasResult = computed(() => rows.value.length > 0 || (graph.value && graph.value.nodes.length > 0) || columns.value.length > 0)

const inspected = ref(null)       // 节点/关系详情
const inspectedType = ref('node')
const inspectedNodeId = ref(null)

// ============ 生命周期 ============
const editorEl = ref(null)
const graphEl = ref(null)
let editor = null
let cy = null
let graphResizeObserver = null

// Monaco 语言：注册 Cypher 高亮（仅一次）
let cypherRegistered = false
function ensureCypherLanguage() {
  if (cypherRegistered) return
  cypherRegistered = true
  monaco.languages.register({ id: 'cypher' })
  monaco.languages.setMonarchTokensProvider('cypher', {
    defaultToken: '',
    tokenPostfix: '.cypher',
    keywords: [
      'MATCH', 'OPTIONAL', 'RETURN', 'WHERE', 'WITH', 'ORDER', 'BY', 'SKIP', 'LIMIT',
      'CREATE', 'MERGE', 'SET', 'REMOVE', 'DELETE', 'DETACH', 'UNION', 'ALL',
      'CALL', 'YIELD', 'AS', 'AND', 'OR', 'NOT', 'XOR', 'IN', 'STARTS', 'ENDS',
      'CONTAINS', 'IS', 'NULL', 'TRUE', 'FALSE', 'DISTINCT', 'ASC', 'DESC',
      'CREATE CONSTRAINT', 'CREATE INDEX', 'DROP', 'LOAD CSV', 'USING PERIODIC COMMIT',
      'SHOW', 'EXPLAIN', 'PROFILE', 'FOREACH', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END',
      'LABELS', 'PROPERTIES', 'TYPE', 'ELEMENTID', 'ID', 'COUNT', 'SUM', 'AVG', 'MIN', 'MAX',
      'COLLECT', 'DISTINCT', 'EXISTS', 'SIZE', 'LENGTH', 'TOINTEGER', 'TOFLOAT',
      'TOSTRING', 'TRIM', 'SUBSTRING', 'REPLACE', 'SPLIT', 'STARTS WITH', 'ENDS WITH'
    ],
    operators: ['=~', '=>', '<>', '!=', '<=', '>=', '<', '>', '=', ':', '->', '<-', '-', '+', '*', '/', '%', '(', ')', '[', ']', '{', '}', ',', '.', '..', '?', '|'],
    symbol: /[=><:+\-*\/%!\[\](){}.,|?\\]/,
    escapes: /\\(?:[tbnrf\\"'])/,
    tokenizer: {
      root: [
        { include: '@whitespace' },
        [/([a-zA-Z_][\w]*(?=\s*\())/, 'type.identifier'],
        [/[a-zA-Z_][a-zA-Z0-9_]*/, {
          cases: {
            '@keywords': 'keyword',
            '@default': 'identifier'
          }
        }],
        [/[a-zA-Z_][a-zA-Z0-9_]*:/, 'type.identifier'],
        { include: '@strings' },
        [/[0-9]+\.[0-9]+/, 'number.float'],
        [/[0-9]+/, 'number'],
        [/\$\w+/, 'variable'],
        [/@symbols/, 'delimiter'],
        [/[@#$%^&*]+/, 'error']
      ],
      whitespace: [
        [/[ \t\r\n]+/, 'white'],
        [/\/\/.*$/, 'comment'],
        [/\/\*/, 'comment', '@comment']
      ],
      comment: [
        [/[^\/*]+/, 'comment'],
        [/\*\//, 'comment', '@pop'],
        [/[\/*]/, 'comment']
      ],
      strings: [
        [/"/, 'string', '@string_double'],
        [/'/, 'string', '@string_single']
      ],
      string_double: [
        [/[^\\"]+/, 'string'],
        [/@escapes/, 'string.escape'],
        [/\\./, 'string.escape.invalid'],
        [/"/, 'string', '@pop']
      ],
      string_single: [
        [/[^\\']+/, 'string'],
        [/@escapes/, 'string.escape'],
        [/\\./, 'string.escape.invalid'],
        [/'/, 'string', '@pop']
      ]
    }
  })
  // Cypher 关键字补全（阶段 1：关键字）
  monaco.languages.registerCompletionItemProvider('cypher', {
    provideCompletionItems(model, position) {
      const word = model.getWordUntilPosition(position)
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: word.startColumn,
        endColumn: word.endColumn
      }
      const prefix = (word.word || '').toUpperCase()
      const kws = [
        'MATCH', 'OPTIONAL MATCH', 'RETURN', 'WHERE', 'WITH', 'ORDER BY', 'SKIP', 'LIMIT',
        'CREATE', 'MERGE', 'SET', 'REMOVE', 'DELETE', 'DETACH DELETE', 'UNION ALL',
        'CALL', 'YIELD', 'AS', 'DISTINCT', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END',
        'SHOW LABELS', 'SHOW RELATIONSHIP TYPES', 'SHOW PROPERTY KEYS'
      ]
      const suggestions = kws
        .filter((k) => !prefix || k.startsWith(prefix))
        .slice(0, 12)
        .map((k) => ({
          label: k,
          kind: monaco.languages.CompletionItemKind.Keyword,
          insertText: k,
          range,
          sortText: '1_' + k
        }))
      // Schema 补全：标签 / 关系类型（阶段 2）
      schemaLabels.value.forEach((l) => {
        suggestions.push({ label: ':' + l, kind: monaco.languages.CompletionItemKind.Class, insertText: ':' + l, range, sortText: '0_' + l })
      })
      schemaRelTypes.value.forEach((r) => {
        suggestions.push({ label: ':' + r, kind: monaco.languages.CompletionItemKind.Class, insertText: ':' + r, range, sortText: '0_' + r })
      })
      return { suggestions }
    }
  })
}

// ============ 数据加载 ============
async function loadDatabases() {
  if (!props.connId) return
  loadingDbs.value = true
  try {
    const res = await neo4jDatabases(props.connId)
    databases.value = res.data || []
    if (!database.value && databases.value.length) {
      database.value = databases.value[0]
    }
    serverStatus.value = `已连接，${databases.value.length} 个数据库`
  } catch (e) {
    serverStatus.value = ''
    ElMessage.error('加载数据库失败: ' + (e.msg || e.message || '未知错误'))
  } finally {
    loadingDbs.value = false
  }
}

const schemaLabels = ref([])
const schemaRelTypes = ref([])
const schemaPropKeys = ref([])
const schemaLabelStats = ref([])
const schemaRelStats = ref([])
const schemaNote = ref('')
async function loadSchema() {
  if (!props.connId) return
  loadingSchema.value = true
  try {
    const res = await neo4jSchema(props.connId, database.value || undefined)
    const data = res.data || {}
    schemaLabels.value = data.labels || []
    schemaRelTypes.value = data.relationshipTypes || []
    schemaPropKeys.value = data.propertyKeys || []
    schemaLabelStats.value = data.labelStats || []
    schemaRelStats.value = data.relTypeStats || []
    schemaNote.value = data.statsNote || ''
  } catch (e) {
    ElMessage.warning('Schema 加载失败: ' + (e.msg || e.message || ''))
  } finally {
    loadingSchema.value = false
  }
}

watch(database, () => {
  loadSchema()
})

// ============ 查询 ============
function parseParams() {
  paramsError.value = ''
  if (!showParams.value || !paramsText.value.trim()) {
    return {}
  }
  try {
    const obj = JSON.parse(paramsText.value)
    if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
      return obj
    }
    paramsError.value = '参数必须是 JSON 对象，如 {"name": "Alice"}'
    return null
  } catch (e) {
    paramsError.value = '参数 JSON 解析失败: ' + e.message
    return null
  }
}

async function runCypher() {
  if (running.value) return
  const cypher = (editor ? editor.getValue() : '').trim()
  if (!cypher) {
    ElMessage.warning('请输入 Cypher 语句')
    editor?.focus()
    return
  }
  const params = parseParams()
  if (params === null) return

  // 写操作二次确认（仅读写连接可写）
  if (writeMode.value && !props.readOnly) {
    try {
      await ElMessageBox.confirm(
        '你已勾选「写操作」，该 Cypher 可能修改或删除数据。确认继续执行？',
        '写操作确认',
        { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }

  errorMsg.value = ''
  running.value = true
  currentExecutionId.value = 'exec-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
  try {
    const res = await neo4jQuery({
      connId: props.connId,
      database: database.value || undefined,
      cypher,
      parameters: params,
      resultLimit: resultLimit.value,
      timeoutSeconds: 60,
      executionId: currentExecutionId.value,
      write: writeMode.value
    })
    const data = res.data || {}
    columns.value = data.columns || []
    rows.value = data.rows || []
    graph.value = data.graph || { nodes: [], rels: [] }
    resultAvailableAfterMs.value = data.resultAvailableAfterMs || 0
    truncated.value = !!data.truncated
    counters.value = data.counters || {}
    errorMsg.value = data.error || ''
    inspected.value = null
    if (data.error) {
      activeView.value = 'summary'
    } else if (hasResult.value && graph.value && graph.value.nodes.length > 0) {
      activeView.value = 'graph'
    } else if (rows.value.length > 0) {
      activeView.value = 'table'
    } else {
      activeView.value = 'summary'
    }
    if (activeView.value === 'graph') {
      await nextTick()
      renderGraph()
    }
    // 记录执行历史（兼作写操作审计：含用户/连接/Cypher/状态/耗时/影响）
    recordHistory(cypher, data)
  } catch (e) {
    errorMsg.value = '执行失败: ' + (e.msg || e.message || '未知错误')
  } finally {
    running.value = false
    currentExecutionId.value = ''
  }
}

async function cancelQuery() {
  if (!currentExecutionId.value) return
  try {
    await neo4jCancel(currentExecutionId.value)
    ElMessage.info('已发送取消请求')
  } catch (e) {
    ElMessage.error('取消失败: ' + (e.msg || e.message))
  }
}

// 生成执行计划（EXPLAIN，不实际执行）
async function runExplain() {
  if (running.value) return
  const cypher = (editor ? editor.getValue() : '').trim()
  if (!cypher) {
    ElMessage.warning('请输入 Cypher 语句')
    return
  }
  const params = parseParams()
  if (params === null) return
  errorMsg.value = ''
  running.value = true
  try {
    const res = await neo4jExplain({
      connId: props.connId,
      database: database.value || undefined,
      cypher,
      parameters: params,
      resultLimit: 50,
      timeoutSeconds: 30,
      executionId: 'explain-' + Date.now()
    })
    const data = res.data || {}
    columns.value = data.columns || []
    rows.value = data.rows || []
    graph.value = data.graph || { nodes: [], rels: [] }
    resultAvailableAfterMs.value = data.resultAvailableAfterMs || 0
    truncated.value = !!data.truncated
    counters.value = {}
    errorMsg.value = data.error || ''
    inspected.value = null
    activeView.value = data.error ? 'summary' : 'table'
  } catch (e) {
    errorMsg.value = '生成执行计划失败: ' + (e.msg || e.message || '未知错误')
    activeView.value = 'summary'
  } finally {
    running.value = false
  }
}

// Schema 项点击 → 插入编辑器光标处
function insertLabel(text) {
  if (!editor || !text) return
  const position = editor.getPosition() || { lineNumber: 1, column: 1 }
  editor.executeEdits('schema-insert', [
    {
      range: new monaco.Range(position.lineNumber, position.column, position.lineNumber, position.column),
      text,
      forceMoveMarkers: true
    }
  ])
  editor.focus()
}

// ============ 最近查询历史 ============
async function loadHistory() {
  if (!props.connId) return
  loadingHistory.value = true
  try {
    const res = await listSqlHistory({ connId: props.connId, pageNum: 1, pageSize: 30 })
    historyList.value = res.data?.records || []
  } catch (e) {
    ElMessage.warning('加载查询历史失败: ' + (e.msg || e.message))
  } finally {
    loadingHistory.value = false
  }
}

// 点击历史回填编辑器
function useHistory(h) {
  if (!h || !h.sqlText) return
  editor?.setValue(h.sqlText)
  showParams.value = false
  editor?.focus()
}

// 删除单条历史
async function removeHistory(h) {
  try {
    await delSqlHistory(h.id)
    historyList.value = historyList.value.filter((x) => x.id !== h.id)
  } catch (e) {
    ElMessage.error('删除历史失败: ' + (e.msg || e.message))
  }
}

// 记录一条执行历史（成功后刷新列表）
function recordHistory(cypher, data) {
  recordSqlHistory({
    connId: props.connId,
    connName: props.connName,
    schemaName: database.value || undefined,
    sqlText: cypher,
    status: data && data.error ? 'FAILED' : 'SUCCESS',
    rowsAffected: (data && data.rows ? data.rows.length : 0),
    durationMs: (data && data.resultAvailableAfterMs) || 0,
    errorMsg: data && data.error ? String(data.error).slice(0, 500) : ''
  }).then(() => {
    loadHistory()
  }).catch(() => {
    // 记录失败不影响主流程
  })
}

function fmtTime(t) {
  if (!t) return ''
  const d = new Date(t)
  if (isNaN(d.getTime())) return String(t)
  return d.toLocaleString()
}

// ============ 编辑器操作 ============
function formatCypher() {
  editor?.getAction('editor.action.formatDocument')?.run()
}
function clearCypher() {
  editor?.setValue('')
  columns.value = []
  rows.value = []
  graph.value = { nodes: [], rels: [] }
  errorMsg.value = ''
  inspected.value = null
  editor?.focus()
}

// ============ 图渲染 ============
function nodeColor(n) {
  const label = (n.labels && n.labels[0]) || 'Node'
  let h = 0
  for (let i = 0; i < label.length; i++) h = (h * 31 + label.charCodeAt(i)) % 360
  return `hsl(${h}, 62%, 48%)`
}

function renderGraph() {
  if (!graphEl.value) return
  if (cy) {
    cy.destroy()
    cy = null
  }
  const elements = []
  ;(graph.value.nodes || []).forEach((n) => {
    elements.push({
      data: { id: n.id, node: n, label: nodeDisplayName(n), color: nodeColor(n) },
      classes: 'node'
    })
  })
  ;(graph.value.rels || []).forEach((r) => {
    elements.push({
      data: { id: r.id, source: r.source, target: r.target, label: r.type, rel: r },
      classes: 'rel'
    })
  })
  if (!elements.length) return
  cy = cytoscape({
    container: graphEl.value,
    elements,
    wheelSensitivity: 0.2,
    style: [
      {
        selector: 'node',
        style: {
          'background-color': 'data(color)',
          'label': 'data(label)',
          'color': '#fff',
          'font-size': 10,
          'text-valign': 'center',
          'text-halign': 'center',
          'text-outline-width': 2,
          'text-outline-color': 'data(color)',
          'width': 34,
          'height': 34
        }
      },
      {
        selector: 'node:selected',
        style: { 'border-width': 3, 'border-color': '#f5a623' }
      },
      {
        selector: 'edge',
        style: {
          'width': 2,
          'line-color': '#aab4c0',
          'target-arrow-color': '#aab4c0',
          'target-arrow-shape': 'triangle',
          'curve-style': 'bezier',
          'label': 'data(label)',
          'font-size': 9,
          'text-rotation': 'autorotate',
          'color': '#889',
          'text-outline-width': 2,
          'text-outline-color': '#f6f6f6'
        }
      },
      {
        selector: 'edge:selected',
        style: { 'line-color': '#f5a623', 'target-arrow-color': '#f5a623' }
      }
    ],
    layout: {
      name: 'cose',
      animate: false,
      fit: true,
      padding: 30,
      nodeRepulsion: 8000,
      idealEdgeLength: 90,
      gravity: 0.25
    }
  })
  // 节点/关系点击 → 属性侧栏
  cy.on('tap', 'node', (evt) => {
    inspected.value = evt.target.data('node')
    inspectedType.value = 'node'
  })
  cy.on('tap', 'edge', (evt) => {
    inspected.value = evt.target.data('rel')
    inspectedType.value = 'rel'
  })
  cy.on('tap', (evt) => {
    if (evt.target === cy) {
      inspected.value = null
    }
  })
}

function fitGraph() {
  if (cy && inspected.value) {
    const id = inspected.value.id
    const el = cy.getElementById(id)
    if (el && el.length) {
      cy.animate({ fit: { eles: el, padding: 60 }, duration: 250 })
    }
  }
}

async function expandNode() {
  if (!inspected.value || inspectedType.value !== 'node') return
  const node = inspected.value
  expanding.value = true
  try {
    const res = await neo4jExpand({
      connId: props.connId,
      database: database.value || undefined,
      nodeId: node.nodeId,
      relLimit: 50,
      nodeLimit: 50,
      executionId: currentExecutionId.value || 'expand'
    })
    const extra = res.data || { nodes: [], rels: [] }
    mergeGraph(extra)
    await nextTick()
    renderGraph()
    // 重新选中原节点
    cy?.getElementById(node.id)?.select()
    inspected.value = node
  } catch (e) {
    ElMessage.error('展开邻居失败: ' + (e.msg || e.message))
  } finally {
    expanding.value = false
  }
}

function mergeGraph(extra) {
  const nodeMap = {}
  const relMap = {}
  ;(graph.value.nodes || []).forEach((n) => (nodeMap[n.id] = n))
  ;(graph.value.rels || []).forEach((r) => (relMap[r.id] = r))
  ;(extra.nodes || []).forEach((n) => {
    if (!nodeMap[n.id]) {
      nodeMap[n.id] = n
      graph.value.nodes.push(n)
    }
  })
  ;(extra.rels || []).forEach((r) => {
    if (!relMap[r.id]) {
      relMap[r.id] = r
      graph.value.rels.push(r)
    }
  })
}

// 表格单元格点击聚焦图元素
function focusGraphCell(cell) {
  if (!cell || !cell.value) return
  const id = cell.value
  const node = (graph.value.nodes || []).find((n) => n.id === id)
  const rel = (graph.value.rels || []).find((r) => r.id === id)
  if (node) {
    inspected.value = node
    inspectedType.value = 'node'
  } else if (rel) {
    inspected.value = rel
    inspectedType.value = 'rel'
  } else {
    return
  }
  activeView.value = 'graph'
  nextTick(() => {
    cy?.getElementById(id)?.select()
    fitGraph()
  })
}

// ============ 格式化辅助 ============
function nodeDisplayName(n) {
  if (!n) return ''
  const props = n.properties || {}
  for (const k of ['name', 'title', 'label']) {
    if (props[k] != null && String(props[k]).trim() !== '') return String(props[k])
  }
  return (n.labels && n.labels.length ? n.labels[0] + ':' : '') + (n.nodeId != null ? n.nodeId : n.id)
}
function relEndpointLabel(id) {
  const n = (graph.value.nodes || []).find((x) => x.id === id)
  return n ? nodeDisplayName(n) : id
}
function fmtValue(v) {
  if (v === null || v === undefined) return 'null'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}
function cellDisplay(cell) {
  if (!cell) return ''
  if (cell.type === 'node') return nodeDisplayName({ id: cell.value, labels: [], properties: {} }) || cell.value
  return cell.value
}
function fmtCell(cell) {
  if (!cell) return ''
  if (cell.type === 'node') return '⛁ ' + (cellDisplay(cell) || cell.value)
  if (cell.type === 'rel') return '→ ' + (cell.value || '')
  if (cell.type === 'list' || cell.type === 'map') return JSON.stringify(cell.value)
  if (cell.type === 'null') return 'null'
  if (cell.type === 'boolean' || cell.type === 'long' || cell.type === 'double') return String(cell.value)
  if (cell.type === 'other') return String(cell.value)
  return String(cell.value ?? '')
}

// ============ 挂载 ============
onMounted(() => {
  ensureCypherLanguage()
  if (editorEl.value) {
    editor = monaco.editor.create(editorEl.value, {
      value: '// 示例：MATCH (n)-[r]->(m) RETURN n, r, m LIMIT 50',
      language: 'cypher',
      theme: 'vs-dark',
      automaticLayout: true,
      minimap: { enabled: false },
      fontSize: 13,
      fontFamily: "'Consolas', 'Courier New', monospace",
      scrollBeyondLastLine: false,
      tabSize: 2,
      renderLineHighlight: 'all',
      scrollbar: { verticalScrollbarSize: 10, horizontalScrollbarSize: 10, useShadows: false }
    })
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => {
      runCypher()
    })
  }
  loadDatabases()
  loadHistory()
  if (typeof ResizeObserver !== 'undefined' && graphEl.value) {
    graphResizeObserver = new ResizeObserver(() => {
      cy?.resize()
    })
    graphResizeObserver.observe(graphEl.value)
  }
})

onBeforeUnmount(() => {
  graphResizeObserver?.disconnect()
  if (cy) {
    cy.destroy()
    cy = null
  }
  if (editor) {
    editor.dispose()
    editor = null
  }
})
</script>

<style scoped>
.neo4j-workbench {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 8px;
  padding: 8px 10px;
  box-sizing: border-box;
  background: #fafbfc;
}
.n4-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
}
.n4-title { font-weight: 600; color: #4581c3; font-size: 13px; }
.n4-conn { font-size: 13px; color: #303133; }
.n4-db { width: 180px; }
.n4-mode { }
.n4-status { font-size: 12px; color: #909399; margin-left: 6px; }
.n4-header-right { margin-left: auto; display: flex; gap: 6px; }

.n4-editor-area {
  display: flex;
  flex-direction: column;
  background: #1e1e1e;
  border: 1px solid #3e3e3e;
  border-radius: 6px;
  overflow: hidden;
}
.n4-editor { height: 160px; min-height: 120px; }
.n4-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: #2d2d2d;
  border-top: 1px solid #3e3e3e;
  flex-wrap: wrap;
}
.n4-toolbar-sep { width: 1px; height: 18px; background: #555; margin: 0 2px; }

.n4-params {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px 10px;
}
.n4-params-label { font-size: 12px; color: #606266; margin-bottom: 6px; }
.n4-params-error { color: #f56c6c; font-size: 12px; margin-top: 4px; }
.n4-error { margin: 0; }

.n4-result { flex: 1; min-height: 0; background: #fff; border: 1px solid #ebeef5; border-radius: 6px; }
.n4-empty { height: 100%; display: flex; align-items: center; justify-content: center; }
.n4-tabs { height: 100%; }
.n4-tabs :deep(.el-tabs__content) { height: calc(100% - 40px); padding: 0; }
.n4-tabs :deep(.el-tab-pane) { height: 100%; }

.n4-graph-layout {
  display: flex;
  height: 100%;
  min-height: 0;
}
.n4-graph {
  flex: 1;
  height: 100%;
  min-width: 0;
  background: #f6f7f9;
}
.n4-inspector {
  width: 320px;
  flex-shrink: 0;
  border-left: 1px solid #ebeef5;
  background: #fff;
  padding: 12px;
  overflow-y: auto;
  box-sizing: border-box;
}
.n4-insp-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.n4-insp-title { font-weight: 600; font-size: 13px; color: #303133; }
.n4-insp-row { margin-bottom: 10px; }
.n4-insp-k { font-size: 12px; color: #909399; display: block; margin-bottom: 3px; }
.n4-insp-v { font-size: 13px; color: #303133; word-break: break-all; }
.n4-insp-muted { color: #c0c4cc; font-size: 12px; }
.n4-insp-props { }
.n4-prop-row { display: flex; gap: 8px; padding: 3px 0; border-bottom: 1px dashed #f0f0f0; }
.n4-prop-k { color: #4581c3; font-size: 12px; min-width: 80px; word-break: break-all; }
.n4-prop-v { color: #303133; font-size: 12px; word-break: break-all; flex: 1; }

.n4-table { height: 100%; }
.n4-cell-graph { color: #4581c3; cursor: pointer; }
.n4-cell-graph:hover { text-decoration: underline; }
.n4-summary { padding: 16px; }

/* Schema 浏览面板 */
.n4-schema {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
  padding: 16px;
  height: 100%;
  box-sizing: border-box;
  align-content: start;
}
.n4-schema-note { grid-column: 1 / -1; font-size: 12px; color: #e6a23c; background: #fdf6ec; border: 1px solid #faecd8; padding: 6px 10px; border-radius: 4px; }
.n4-schema-col { border: 1px solid #ebeef5; border-radius: 6px; padding: 8px 10px; background: #fafafa; max-height: 320px; display: flex; flex-direction: column; }
.n4-schema-title { font-size: 12px; font-weight: 600; color: #303133; margin-bottom: 6px; flex-shrink: 0; }
.n4-schema-list { overflow-y: auto; flex: 1; }
.n4-schema-item { display: flex; justify-content: space-between; align-items: center; padding: 3px 6px; border-radius: 4px; cursor: pointer; font-size: 12px; color: #4581c3; }
.n4-schema-item:hover { background: #ecf5ff; }
.n4-schema-name { word-break: break-all; }
.n4-schema-count { color: #909399; background: #f0f2f5; border-radius: 8px; padding: 0 6px; font-size: 11px; flex-shrink: 0; }
.n4-schema-muted { color: #c0c4cc; font-size: 12px; }

/* 最近查询历史 */
.n4-history { height: 100%; display: flex; flex-direction: column; padding: 12px 16px; box-sizing: border-box; }
.n4-history-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; flex-shrink: 0; }
.n4-history-title { font-size: 12px; color: #606266; }
.n4-history-list { flex: 1; overflow-y: auto; }
.n4-history-item { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border: 1px solid #ebeef5; border-radius: 6px; margin-bottom: 6px; background: #fff; }
.n4-history-main { flex: 1; min-width: 0; cursor: pointer; }
.n4-history-sql { font-family: Consolas, monospace; font-size: 12px; color: #303133; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.n4-history-meta { display: flex; align-items: center; gap: 8px; margin-top: 4px; flex-wrap: wrap; }
.n4-history-rows, .n4-history-time, .n4-history-date { font-size: 11px; color: #909399; }
.n4-history-err { font-size: 11px; color: #f56c6c; max-width: 300px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>
