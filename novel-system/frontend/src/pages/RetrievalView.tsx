import React, { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Form,
  InputNumber,
  Progress,
  Row,
  Space,
  Statistic,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  DatabaseOutlined,
  DeleteOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import { useParams } from 'react-router-dom'
import { retrievalApi, taskApi } from '../services/api'

const { Paragraph, Text, Title } = Typography

const RetrievalView: React.FC = () => {
  const { projectId } = useParams<{ projectId: string }>()
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [rebuilding, setRebuilding] = useState(false)
  const [syncing, setSyncing] = useState(false)
  const [evaluating, setEvaluating] = useState(false)
  const [invalidating, setInvalidating] = useState(false)
  const [overview, setOverview] = useState<any>(null)
  const [config, setConfig] = useState<any>({})
  const [contextPack, setContextPack] = useState<any>(null)
  const [form] = Form.useForm()

  useEffect(() => {
    loadOverview()
  }, [projectId])

  const indexes = overview?.indexes || {}
  const packs = overview?.contextPacks || []
  const latestTasks = overview?.latestTasks || []
  const qualityReport = overview?.qualityReport || {}
  const latestQuality = indexes?.hybrid?.quality_evaluation || indexes?.rebuildReport?.quality_evaluation || {}
  const latestBudget = indexes?.hybrid?.citation_budget || indexes?.rebuildReport?.citation_budget || {}

  const indexStats = useMemo(() => {
    return ['bm25', 'vector', 'hybrid'].map((type) => {
      const item = indexes[type] || { indexType: type }
      return {
        ...item,
        indexType: item.indexType || type,
        documentCount: item.document_count ?? item.documentCount ?? item.documents?.length ?? 0,
        engine: item.engine || (type === 'bm25' ? 'keyword' : type),
      }
    })
  }, [indexes])

  const loadOverview = async () => {
    if (!projectId) return
    try {
      setLoading(true)
      const data = await retrievalApi.getOverview(projectId)
      setOverview(data)
      setConfig(data?.config || {})
      form.setFieldsValue({
        use_keyword: data?.config?.use_keyword ?? true,
        use_vector: data?.config?.use_vector ?? true,
        use_graph: data?.config?.use_graph ?? true,
        use_rerank: data?.config?.use_rerank ?? true,
        graph_hops: data?.config?.graph_hops ?? 2,
        top_k: data?.config?.top_k ?? 12,
        max_context_chars: data?.config?.max_context_chars ?? 6000,
        max_retrieval_results: data?.config?.max_retrieval_results ?? 8,
        max_retrieval_chars: data?.config?.max_retrieval_chars ?? 2400,
        max_result_chars: data?.config?.max_result_chars ?? 220,
        max_sample_quote_chars: data?.config?.max_sample_quote_chars ?? 80,
        max_results_per_source_type: data?.config?.max_results_per_source_type ?? 4,
      })
    } catch (error) {
      setOverview(null)
    } finally {
      setLoading(false)
    }
  }

  const saveConfig = async () => {
    if (!projectId) return
    try {
      setSaving(true)
      const values = await form.validateFields()
      const data = await retrievalApi.updateConfig(projectId, values)
      setConfig(data)
      message.success('检索配置已保存')
      await loadOverview()
    } catch (error) {
      message.error('保存检索配置失败')
    } finally {
      setSaving(false)
    }
  }

  const rebuild = async () => {
    if (!projectId) return
    try {
      setRebuilding(true)
      const values = form.getFieldsValue()
      const task: any = await retrievalApi.rebuild(projectId, {
        ...values,
        top_k: values.top_k || 12,
      })
      message.success('检索索引重建任务已启动')
      await pollTask(task.id)
    } catch (error) {
      message.error('启动检索重建失败')
    } finally {
      setRebuilding(false)
    }
  }

  const syncRetrievalDb = async () => {
    if (!projectId) return
    try {
      setSyncing(true)
      message.loading({ content: '正在同步检索数据库', key: 'retrieval-sync' })
      await retrievalApi.syncDb(projectId, {})
      message.success({ content: '检索数据库已同步', key: 'retrieval-sync' })
    } catch (error) {
      message.error({ content: '同步检索数据库失败', key: 'retrieval-sync' })
    } finally {
      setSyncing(false)
    }
  }

  const evaluateRetrievalQuality = async () => {
    if (!projectId) return
    try {
      setEvaluating(true)
      message.loading({ content: '正在评估检索质量', key: 'retrieval-quality' })
      await retrievalApi.evaluateQuality(projectId, {})
      message.success({ content: '检索质量评估已生成', key: 'retrieval-quality' })
      await loadOverview()
    } catch (error) {
      message.error({ content: '检索质量评估失败', key: 'retrieval-quality' })
    } finally {
      setEvaluating(false)
    }
  }

  const invalidateRetrievalCaches = async () => {
    if (!projectId) return
    try {
      setInvalidating(true)
      message.loading({ content: '正在清理旧检索缓存', key: 'retrieval-invalidate' })
      const result: any = await retrievalApi.invalidate(projectId, {
        actor: 'human',
        reason: 'manual invalidation from RetrievalView',
        clearContextPacks: true,
        clearQualityReport: true,
        clearHybridSummary: true,
        clearRebuildReport: true,
      })
      message.success({
        content: `已清理 ${result?.deletedCount || 0} 个旧检索产物`,
        key: 'retrieval-invalidate',
      })
      await loadOverview()
    } catch (error) {
      message.error({ content: '清理旧检索缓存失败', key: 'retrieval-invalidate' })
    } finally {
      setInvalidating(false)
    }
  }

  const pollTask = async (taskId: string) => {
    if (!projectId) return
    for (let i = 0; i < 30; i += 1) {
      await new Promise((resolve) => setTimeout(resolve, 1500))
      const task: any = await taskApi.getStatus(projectId, taskId)
      if (['SUCCESS', 'FAILED', 'CANCELLED'].includes(task.status)) {
        if (task.status === 'SUCCESS') {
          message.success('检索索引重建完成')
          await loadOverview()
        } else {
          message.error('检索索引重建失败')
        }
        return
      }
    }
    message.warning('任务仍在运行，请稍后刷新查看')
  }

  const openContextPack = async (record: any) => {
    if (!projectId || !record.id) return
    try {
      const data = await retrievalApi.getContextPack(projectId, record.id)
      setContextPack(data)
    } catch (error) {
      message.error('加载上下文包失败')
    }
  }

  const qualityColor = (status?: string) => {
    if (status === 'good') return 'success'
    if (status === 'needs_review') return 'warning'
    if (status === 'poor') return 'error'
    if (status === 'pass') return 'success'
    if (status === 'warn') return 'warning'
    if (status === 'fail') return 'error'
    return 'default'
  }

  const percent = (value: any) => {
    const numeric = Number(value || 0)
    if (!Number.isFinite(numeric)) return 0
    return Math.max(0, Math.min(100, Math.round(numeric * 100)))
  }

  const indexColumns = [
    {
      title: '索引',
      dataIndex: 'indexType',
      key: 'indexType',
      render: (value: string) => <Tag color="blue">{value}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'exists',
      key: 'exists',
      render: (exists: boolean) => <Tag color={exists ? 'success' : 'default'}>{exists ? '已生成' : '未生成'}</Tag>,
    },
    { title: '引擎', dataIndex: 'engine', key: 'engine' },
    { title: '文档数', dataIndex: 'documentCount', key: 'documentCount', width: 100 },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt' },
    { title: '路径', dataIndex: 'path', key: 'path' },
  ]

  const packColumns = [
    { title: '上下文包', dataIndex: 'id', key: 'id' },
    { title: '书籍', dataIndex: 'bookId', key: 'bookId' },
    { title: '卷', dataIndex: 'volumeNumber', key: 'volumeNumber', width: 70 },
    { title: '章', dataIndex: 'chapterNumber', key: 'chapterNumber', width: 70 },
    {
      title: '来源',
      dataIndex: 'sources',
      key: 'sources',
      render: (sources: any) => sources ? (
        <Space wrap>
          <Tag>doc {sources.documents_indexed || 0}</Tag>
          <Tag>kw {sources.keyword_results || 0}</Tag>
          <Tag>vec {sources.vector_results || 0}</Tag>
          <Tag>rerank {sources.reranked_results || 0}</Tag>
        </Space>
      ) : '-',
    },
    {
      title: '质量',
      dataIndex: 'qualityEvaluation',
      key: 'qualityEvaluation',
      width: 120,
      render: (quality: any, record: any) => {
        const status = quality?.status || record.sources?.quality_status
        const score = quality?.score ?? record.sources?.quality_score
        return score !== undefined ? (
          <Tag color={qualityColor(status)}>{status || 'unknown'} {score}</Tag>
        ) : '-'
      },
    },
    {
      title: '预算',
      dataIndex: 'citationBudget',
      key: 'citationBudget',
      width: 130,
      render: (budget: any) => {
        const usage = budget?.usage || {}
        const utilization = usage.context_utilization ?? usage.retrieval_budget_utilization
        return utilization !== undefined ? (
          <Tag color={percent(utilization) > 90 ? 'warning' : 'green'}>{percent(utilization)}%</Tag>
        ) : '-'
      },
    },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt' },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_: any, record: any) => (
        <Button type="link" onClick={() => openContextPack(record)}>
          查看
        </Button>
      ),
    },
  ]

  const taskColumns = [
    { title: '任务', dataIndex: 'id', key: 'id' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => <Tag color={status === 'SUCCESS' ? 'success' : status === 'FAILED' ? 'error' : 'processing'}>{status}</Tag>,
    },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt' },
    { title: '完成时间', dataIndex: 'finishedAt', key: 'finishedAt' },
  ]

  const qualityCheckColumns = [
    {
      title: '检查项',
      dataIndex: 'title',
      key: 'title',
      width: 160,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: string) => <Tag color={qualityColor(status)}>{status || 'unknown'}</Tag>,
    },
    { title: '结论', dataIndex: 'message', key: 'message' },
    { title: '建议', dataIndex: 'recommendation', key: 'recommendation' },
  ]

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
        <div>
          <Title level={3} style={{ marginBottom: 4 }}>检索与上下文</Title>
          <Text type="secondary">管理关键词、向量、图谱融合检索产物与章节上下文包。</Text>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={loadOverview} loading={loading}>
            刷新
          </Button>
          <Button icon={<DatabaseOutlined />} onClick={syncRetrievalDb} loading={syncing}>
            同步检索数据库
          </Button>
          <Button icon={<SearchOutlined />} onClick={evaluateRetrievalQuality} loading={evaluating}>
            评估检索质量
          </Button>
          <Button icon={<DeleteOutlined />} onClick={invalidateRetrievalCaches} loading={invalidating}>
            清理旧缓存
          </Button>
          <Button type="primary" icon={<SearchOutlined />} onClick={rebuild} loading={rebuilding}>
            重建索引
          </Button>
        </Space>
      </Space>

      <Row gutter={16}>
        <Col span={6}>
          <Card>
            <Statistic title="上下文包" value={packs.length} prefix={<SearchOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="BM25 文档" value={indexes?.bm25?.document_count || 0} prefix={<DatabaseOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="向量文档" value={indexes?.vector?.document_count || 0} prefix={<DatabaseOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="检索质量"
              value={qualityReport?.exists ? qualityReport.score : latestQuality?.score ?? 0}
              suffix={qualityReport?.exists ? qualityReport.status : latestQuality?.status || ''}
            />
          </Card>
        </Col>
      </Row>

      {(latestQuality?.warnings?.length || latestBudget?.warnings?.length) ? (
        <Alert
          type={latestQuality?.status === 'poor' ? 'error' : 'warning'}
          showIcon
          message="检索质量与引用预算提示"
          description={[...(latestQuality?.warnings || []), ...(latestBudget?.warnings || [])].join('；')}
        />
      ) : null}

      <Card title="检索质量评估">
        {qualityReport?.exists ? (
          <Space direction="vertical" style={{ width: '100%' }} size="middle">
            <Row gutter={16}>
              <Col span={6}>
                <Statistic title="报告评分" value={qualityReport.score ?? 0} />
              </Col>
              <Col span={6}>
                <Statistic title="检查项" value={(qualityReport.checks || []).length} />
              </Col>
              <Col span={6}>
                <Statistic title="上下文包" value={qualityReport.coverage?.contextPackCount ?? 0} />
              </Col>
              <Col span={6}>
                <Space direction="vertical" size={4}>
                  <Text type="secondary">状态</Text>
                  <Tag color={qualityColor(qualityReport.status)}>{qualityReport.status || 'unknown'}</Tag>
                </Space>
              </Col>
            </Row>
            {(qualityReport.warnings || []).length ? (
              <Alert
                type={qualityReport.status === 'poor' ? 'error' : 'warning'}
                showIcon
                message="质量评估告警"
                description={(qualityReport.warnings || []).join('；')}
              />
            ) : null}
            {(qualityReport.recommendations || []).length ? (
              <Alert
                type="info"
                showIcon
                message="改进建议"
                description={(qualityReport.recommendations || []).join('；')}
              />
            ) : null}
            <Table
              columns={qualityCheckColumns}
              dataSource={qualityReport.checks || []}
              rowKey="key"
              pagination={false}
              size="small"
            />
            <Text type="secondary">产物：{qualityReport.path}</Text>
          </Space>
        ) : (
          <Empty description="尚未生成质量评估报告" />
        )}
      </Card>

      <Card title="检索配置">
        <Form form={form} layout="inline">
          <Form.Item name="use_keyword" label="关键词" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="use_vector" label="向量" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="use_graph" label="图谱" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="use_rerank" label="Rerank" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="graph_hops" label="图谱跳数">
            <InputNumber min={1} max={5} style={{ width: 90 }} />
          </Form.Item>
          <Form.Item name="top_k" label="Top K">
            <InputNumber min={1} max={50} style={{ width: 90 }} />
          </Form.Item>
          <Form.Item name="max_context_chars" label="上下文预算">
            <InputNumber min={1200} max={20000} step={500} style={{ width: 110 }} />
          </Form.Item>
          <Form.Item name="max_retrieval_results" label="引用条数">
            <InputNumber min={1} max={50} style={{ width: 90 }} />
          </Form.Item>
          <Form.Item name="max_retrieval_chars" label="引用预算">
            <InputNumber min={200} max={20000} step={200} style={{ width: 110 }} />
          </Form.Item>
          <Form.Item name="max_result_chars" label="单条长度">
            <InputNumber min={40} max={800} step={20} style={{ width: 100 }} />
          </Form.Item>
          <Form.Item name="max_sample_quote_chars" label="样本摘录">
            <InputNumber min={15} max={240} step={5} style={{ width: 100 }} />
          </Form.Item>
          <Form.Item name="max_results_per_source_type" label="单类上限">
            <InputNumber min={1} max={20} style={{ width: 90 }} />
          </Form.Item>
          <Form.Item>
            <Button onClick={saveConfig} loading={saving}>保存配置</Button>
          </Form.Item>
        </Form>
        {config?.path && (
          <Alert
            style={{ marginTop: 16 }}
            type="info"
            showIcon
            message={config.path}
            description={`配置文件${config.exists ? '已存在' : '尚未写入'}，重建索引和章节上下文构建都会读取这份配置。`}
          />
        )}
      </Card>

      <Card title="索引摘要">
        <Table
          columns={indexColumns}
          dataSource={indexStats}
          rowKey="indexType"
          loading={loading}
          pagination={false}
        />
      </Card>

      <Card title="章节上下文包">
        {packs.length ? (
          <Table
            columns={packColumns}
            dataSource={packs}
            rowKey="id"
            loading={loading}
          />
        ) : (
          <Empty description="尚未生成上下文包，可先生成章节或重建索引。" />
        )}
      </Card>

      <Card title="索引重建任务">
        <Table
          columns={taskColumns}
          dataSource={latestTasks}
          rowKey="id"
          pagination={false}
          locale={{ emptyText: '暂无检索重建任务' }}
        />
      </Card>

      <Drawer
        title={contextPack?.id || '上下文包详情'}
        open={!!contextPack}
        width={780}
        onClose={() => setContextPack(null)}
      >
        {contextPack ? (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Descriptions bordered column={2} size="small">
              <Descriptions.Item label="路径" span={2}>{contextPack.path}</Descriptions.Item>
              <Descriptions.Item label="书籍">{contextPack.book_id}</Descriptions.Item>
              <Descriptions.Item label="章节">{contextPack.volume_number}-{contextPack.chapter_number}</Descriptions.Item>
              <Descriptions.Item label="构建时间" span={2}>{contextPack.built_at}</Descriptions.Item>
            </Descriptions>
            <Card title="检索计划" size="small">
              <Paragraph style={{ whiteSpace: 'pre-wrap' }}>
                {JSON.stringify(contextPack.retrieval_plan || {}, null, 2)}
              </Paragraph>
            </Card>
            <Card title="质量评估" size="small">
              <Space direction="vertical" style={{ width: '100%' }}>
                <Space wrap>
                  <Tag color={qualityColor(contextPack.quality_evaluation?.status)}>
                    {contextPack.quality_evaluation?.status || 'unknown'}
                  </Tag>
                  <Text strong>score {contextPack.quality_evaluation?.score ?? '-'}</Text>
                </Space>
                <Progress
                  percent={Number(contextPack.quality_evaluation?.score || 0)}
                  size="small"
                  status={contextPack.quality_evaluation?.status === 'poor' ? 'exception' : 'normal'}
                />
                {(contextPack.quality_evaluation?.warnings || []).length ? (
                  <Alert
                    type="warning"
                    showIcon
                    message={(contextPack.quality_evaluation?.warnings || []).join('；')}
                  />
                ) : null}
                <Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                  {JSON.stringify(contextPack.quality_evaluation?.metrics || {}, null, 2)}
                </Paragraph>
              </Space>
            </Card>
            <Card title="引用预算" size="small">
              <Space direction="vertical" style={{ width: '100%' }}>
                <Descriptions bordered column={2} size="small">
                  <Descriptions.Item label="选中引用">
                    {contextPack.citation_budget?.usage?.selected_result_count ?? 0}/
                    {contextPack.citation_budget?.usage?.raw_result_count ?? 0}
                  </Descriptions.Item>
                  <Descriptions.Item label="上下文占用">
                    {percent(contextPack.citation_budget?.usage?.context_utilization)}%
                  </Descriptions.Item>
                  <Descriptions.Item label="引用字符">
                    {contextPack.citation_budget?.usage?.retrieval_chars ?? 0}/
                    {contextPack.citation_budget?.usage?.max_retrieval_chars ?? 0}
                  </Descriptions.Item>
                  <Descriptions.Item label="引用编号">
                    {(contextPack.citation_budget?.usage?.included_citation_ids || contextPack.citation_budget?.usage?.selected_citation_ids || []).join(', ') || '-'}
                  </Descriptions.Item>
                </Descriptions>
                <Progress percent={percent(contextPack.citation_budget?.usage?.context_utilization)} size="small" />
                {(contextPack.citation_budget?.warnings || []).length ? (
                  <Alert
                    type="warning"
                    showIcon
                    message={(contextPack.citation_budget?.warnings || []).join('；')}
                  />
                ) : null}
              </Space>
            </Card>
            <Card title="Top Rerank 结果" size="small">
              {(contextPack.retrieval_results || []).slice(0, 8).map((item: any) => (
                <Card key={item.doc_id} size="small" style={{ marginBottom: 8 }}>
                  <Space direction="vertical" style={{ width: '100%' }}>
                    <Space wrap>
                      <Tag color="blue">{item.source_type}</Tag>
                      {item.citation_id ? <Tag color="green">{item.citation_id}</Tag> : null}
                      {(item.retrieval_sources || []).map((source: string) => <Tag key={source}>{source}</Tag>)}
                      <Text strong>{item.title}</Text>
                      <Text type="secondary">score {item.rerank_score ?? item.score}</Text>
                    </Space>
                    <Paragraph style={{ marginBottom: 0 }}>{item.snippet}</Paragraph>
                  </Space>
                </Card>
              ))}
            </Card>
          </Space>
        ) : null}
      </Drawer>
    </Space>
  )
}

export default RetrievalView
