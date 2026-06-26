import React, { useEffect, useMemo, useState } from 'react'
import {
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Input,
  Modal,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  DeleteOutlined,
  DiffOutlined,
  DownloadOutlined,
  EyeOutlined,
  FolderOpenOutlined,
  HistoryOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import { useParams } from 'react-router-dom'
import { artifactApi } from '../services/api'

const { Paragraph, Text, Title } = Typography
const { Search } = Input

const categoryColors: Record<string, string> = {
  samples: 'blue',
  analysis: 'purple',
  skills: 'cyan',
  novel: 'green',
  memory: 'orange',
  graph: 'magenta',
  indexes: 'volcano',
  logs: 'default',
}

const ArtifactView: React.FC = () => {
  const { projectId } = useParams<{ projectId: string }>()
  const [loading, setLoading] = useState(false)
  const [overview, setOverview] = useState<any>(null)
  const [items, setItems] = useState<any[]>([])
  const [category, setCategory] = useState<string>('all')
  const [query, setQuery] = useState('')
  const [drawer, setDrawer] = useState<any>(null)
  const [auditDrawer, setAuditDrawer] = useState<any[]>([])
  const [auditOpen, setAuditOpen] = useState(false)
  const [diffDrawer, setDiffDrawer] = useState<any>(null)
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([])
  const [actionLoading, setActionLoading] = useState(false)

  useEffect(() => {
    loadData()
  }, [projectId, category])

  const categories = overview?.categories || []
  const totalCount = useMemo(
    () => categories.reduce((sum: number, item: any) => sum + (item.count || 0), 0),
    [categories]
  )
  const totalBytes = useMemo(
    () => categories.reduce((sum: number, item: any) => sum + (item.bytes || 0), 0),
    [categories]
  )
  const selectedItems = useMemo(
    () => items.filter((item) => selectedRowKeys.includes(item.path)),
    [items, selectedRowKeys]
  )

  const loadData = async (nextQuery = query) => {
    if (!projectId) return
    try {
      setLoading(true)
      const [overviewData, listData] = await Promise.all([
        artifactApi.getOverview(projectId),
        artifactApi.list(projectId, {
          category: category === 'all' ? undefined : category,
          query: nextQuery || undefined,
          limit: 300,
        }),
      ])
      setOverview(overviewData)
      setItems(listData?.items || [])
      setSelectedRowKeys([])
    } catch (error) {
      message.error('加载产物失败')
    } finally {
      setLoading(false)
    }
  }

  const openArtifact = async (record: any) => {
    if (!projectId) return
    if (!record.previewable) {
      message.info('该文件不支持文本预览，可直接下载')
      return
    }
    try {
      const detail = await artifactApi.view(projectId, record.path)
      setDrawer(detail)
    } catch (error) {
      message.error('打开产物失败')
    }
  }

  const startDownload = async (record: any, allowSensitive = false) => {
    if (!projectId) return
    try {
      const blob = await artifactApi.download(projectId, record.path, allowSensitive ? { allowSensitive: true } : undefined)
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = record.name || 'artifact'
      link.click()
      window.URL.revokeObjectURL(url)
      message.success('下载已开始')
    } catch (error) {
      message.error('下载产物失败')
    }
  }

  const downloadArtifact = async (record: any) => {
    if (!record.sensitive) {
      await startDownload(record)
      return
    }
    Modal.confirm({
      title: '下载敏感样本产物？',
      content: '该文件属于样本原文或切片，默认受保护。继续下载会显式授权完整内容访问，并保留后端敏感访问策略校验。',
      okText: '授权下载',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: () => startDownload(record, true),
    })
  }

  const startBulkDownload = async (allowSensitive = false) => {
    if (!projectId || selectedItems.length === 0) return
    try {
      setActionLoading(true)
      const blob = await artifactApi.bulkDownload(projectId, {
        paths: selectedItems.map((item) => item.path),
        allowSensitive,
        actor: 'human',
        reason: allowSensitive ? 'ArtifactView批量导出含敏感样本授权' : 'ArtifactView批量导出',
      })
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `artifacts_${Date.now()}.zip`
      link.click()
      window.URL.revokeObjectURL(url)
      message.success(allowSensitive ? '批量导出已开始，已包含授权的敏感样本产物' : '批量导出已开始，敏感样本原文会自动跳过')
    } catch (error) {
      message.error('批量导出失败')
    } finally {
      setActionLoading(false)
    }
  }

  const downloadSelected = async () => {
    if (selectedItems.some((item) => item.sensitive)) {
      Modal.confirm({
        title: '批量导出包含敏感产物',
        content: `当前选择中有 ${selectedItems.filter((item) => item.sensitive).length} 个敏感样本产物。取消授权时这些文件会被后端跳过；继续授权则会包含完整内容。`,
        okText: '授权并导出',
        cancelText: '跳过敏感项导出',
        okButtonProps: { danger: true },
        onOk: () => startBulkDownload(true),
        onCancel: () => startBulkDownload(false),
      })
      return
    }
    await startBulkDownload(false)
  }

  const archiveArtifact = (record: any) => {
    if (!projectId) return
    Modal.confirm({
      title: '归档这个产物？',
      content: '归档会把文件移动到 artifacts/archive，并写入审计日志；不会物理删除。',
      okText: '归档',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          setActionLoading(true)
          await artifactApi.archive(projectId, {
            path: record.path,
            actor: 'human',
            reason: 'ArtifactView归档',
          })
          message.success('产物已归档')
          await loadData()
        } catch (error) {
          message.error('归档产物失败')
        } finally {
          setActionLoading(false)
        }
      },
    })
  }

  const runDiff = async (allowSensitive = false) => {
    if (!projectId || selectedItems.length !== 2) {
      message.warning('请选择两个文本产物进行对比')
      return
    }
    try {
      setActionLoading(true)
      const result = await artifactApi.diff(projectId, {
        leftPath: selectedItems[0].path,
        rightPath: selectedItems[1].path,
        allowSensitive,
      })
      setDiffDrawer(result)
    } catch (error) {
      message.error('产物差异对比失败')
    } finally {
      setActionLoading(false)
    }
  }

  const openDiff = async () => {
    if (selectedItems.length !== 2) {
      message.warning('请选择两个文本产物进行对比')
      return
    }
    if (selectedItems.some((item) => item.sensitive)) {
      Modal.confirm({
        title: '对比敏感样本产物？',
        content: '本次对比包含样本原文或切片。继续对比会显式授权读取完整文本内容。',
        okText: '授权对比',
        cancelText: '取消',
        okButtonProps: { danger: true },
        onOk: () => runDiff(true),
      })
      return
    }
    await runDiff(false)
  }

  const openAudit = async () => {
    if (!projectId) return
    try {
      setActionLoading(true)
      const result = await artifactApi.audit(projectId, { limit: 100 })
      setAuditDrawer(result?.items || [])
      setAuditOpen(true)
    } catch (error) {
      message.error('加载审计日志失败')
    } finally {
      setActionLoading(false)
    }
  }

  const columns = [
    {
      title: '类型',
      dataIndex: 'category',
      key: 'category',
      width: 100,
      render: (value: string, record: any) => (
        <Space size={4}>
          <Tag color={categoryColors[value] || 'default'}>{value}</Tag>
          {record.sensitive && <Tag color="red">敏感</Tag>}
        </Space>
      ),
    },
    { title: '文件', dataIndex: 'name', key: 'name', width: 220 },
    { title: '路径', dataIndex: 'path', key: 'path' },
    { title: '扩展名', dataIndex: 'extension', key: 'extension', width: 90 },
    {
      title: '大小',
      dataIndex: 'size',
      key: 'size',
      width: 110,
      render: (value: number) => formatBytes(value),
    },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 190 },
    {
      title: '操作',
      key: 'action',
      width: 220,
      render: (_: any, record: any) => (
        <Space wrap>
          <Button type="link" icon={<EyeOutlined />} onClick={() => openArtifact(record)}>
            预览
          </Button>
          <Button type="link" icon={<DownloadOutlined />} onClick={() => downloadArtifact(record)}>
            下载
          </Button>
          <Button type="link" danger icon={<DeleteOutlined />} onClick={() => archiveArtifact(record)}>
            归档
          </Button>
        </Space>
      ),
    },
  ]

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
        <div>
          <Title level={3} style={{ marginBottom: 4 }}>产物管理</Title>
          <Text type="secondary">统一查看、预览和下载项目 workspace 中的样本、分析、Skill、正文、记忆、图谱与索引产物。</Text>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => loadData()} loading={loading}>
          刷新
        </Button>
      </Space>

      <Row gutter={16}>
        <Col span={8}>
          <Card>
            <Statistic title="产物文件" value={totalCount} prefix={<FolderOpenOutlined />} />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic title="总大小" value={formatBytes(totalBytes)} />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic title="当前列表" value={items.length} />
          </Card>
        </Col>
      </Row>

      <Card>
        <Space wrap style={{ marginBottom: 16 }}>
          <Select
            value={category}
            style={{ width: 180 }}
            onChange={setCategory}
            options={[
              { label: '全部产物', value: 'all' },
              ...categories.map((item: any) => ({
                label: `${item.title} (${item.count || 0})`,
                value: item.key,
              })),
            ]}
          />
          <Search
            allowClear
            placeholder="按路径搜索"
            style={{ width: 360 }}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onSearch={(value) => loadData(value)}
          />
          <Button
            icon={<DownloadOutlined />}
            onClick={downloadSelected}
            disabled={selectedItems.length === 0}
            loading={actionLoading}
          >
            批量导出
          </Button>
          <Button
            icon={<DiffOutlined />}
            onClick={openDiff}
            disabled={selectedItems.length !== 2}
            loading={actionLoading}
          >
            对比
          </Button>
          <Button icon={<HistoryOutlined />} onClick={openAudit} loading={actionLoading}>
            审计
          </Button>
        </Space>
        {items.length ? (
          <Table
            columns={columns}
            dataSource={items}
            rowKey="path"
            loading={loading}
            rowSelection={{
              selectedRowKeys,
              onChange: setSelectedRowKeys,
            }}
            pagination={{ pageSize: 12 }}
          />
        ) : (
          <Empty description="暂无产物" />
        )}
      </Card>

      <Drawer
        title={drawer?.name || '产物预览'}
        open={!!drawer}
        width={820}
        onClose={() => setDrawer(null)}
      >
        {drawer ? (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label="路径" span={2}>{drawer.path}</Descriptions.Item>
              <Descriptions.Item label="类型">{drawer.category}</Descriptions.Item>
              <Descriptions.Item label="大小">{formatBytes(drawer.size)}</Descriptions.Item>
              <Descriptions.Item label="更新时间" span={2}>{drawer.updatedAt}</Descriptions.Item>
            </Descriptions>
            {drawer.truncated && <Tag color="warning">内容已截断显示</Tag>}
            {drawer.redacted && <Tag color="red">敏感内容已保护，仅显示短预览</Tag>}
            <Paragraph style={{ whiteSpace: 'pre-wrap', maxHeight: 560, overflow: 'auto' }}>
              {drawer.content || '无可预览文本'}
            </Paragraph>
          </Space>
        ) : null}
      </Drawer>

      <Drawer
        title="产物差异对比"
        open={!!diffDrawer}
        width={960}
        onClose={() => setDiffDrawer(null)}
      >
        {diffDrawer ? (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label="左侧" span={2}>{diffDrawer.left?.path}</Descriptions.Item>
              <Descriptions.Item label="右侧" span={2}>{diffDrawer.right?.path}</Descriptions.Item>
              <Descriptions.Item label="新增行">{diffDrawer.addedLines}</Descriptions.Item>
              <Descriptions.Item label="删除行">{diffDrawer.removedLines}</Descriptions.Item>
            </Descriptions>
            <div style={{ maxHeight: 620, overflow: 'auto', fontFamily: 'monospace', fontSize: 12 }}>
              {(diffDrawer.diff || []).map((line: any, index: number) => {
                const color = line.type === 'added' ? '#f6ffed' : line.type === 'removed' ? '#fff1f0' : '#fff'
                const prefix = line.type === 'added' ? '+' : line.type === 'removed' ? '-' : ' '
                return (
                  <div key={index} style={{ background: color, whiteSpace: 'pre-wrap', padding: '2px 8px' }}>
                    <Text type="secondary" style={{ marginRight: 8 }}>
                      {String(line.leftLine || '').padStart(4, ' ')} {String(line.rightLine || '').padStart(4, ' ')}
                    </Text>
                    {prefix} {line.text}
                  </div>
                )
              })}
            </div>
          </Space>
        ) : null}
      </Drawer>

      <Drawer
        title="产物审计"
        open={auditOpen}
        width={820}
        onClose={() => setAuditOpen(false)}
      >
        <Table
          rowKey="eventId"
          dataSource={auditDrawer}
          size="small"
          pagination={{ pageSize: 12 }}
          columns={[
            { title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 180 },
            { title: '动作', dataIndex: 'action', key: 'action', width: 120, render: (value: string) => <Tag>{value}</Tag> },
            { title: '操作者', dataIndex: 'actor', key: 'actor', width: 100 },
            { title: '来源路径', dataIndex: 'sourcePath', key: 'sourcePath', ellipsis: true },
            { title: '说明', dataIndex: 'reason', key: 'reason', ellipsis: true },
          ]}
          locale={{ emptyText: '暂无审计记录' }}
        />
      </Drawer>
    </Space>
  )
}

function formatBytes(value?: number) {
  const size = Number(value || 0)
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

export default ArtifactView
