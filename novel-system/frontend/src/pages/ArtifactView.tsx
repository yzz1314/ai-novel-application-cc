import React, { useEffect, useMemo, useState } from 'react'
import {
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Input,
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
  DownloadOutlined,
  EyeOutlined,
  FolderOpenOutlined,
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

  const downloadArtifact = async (record: any) => {
    if (!projectId) return
    try {
      const blob = await artifactApi.download(projectId, record.path)
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

  const columns = [
    {
      title: '类型',
      dataIndex: 'category',
      key: 'category',
      width: 100,
      render: (value: string) => <Tag color={categoryColors[value] || 'default'}>{value}</Tag>,
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
      width: 140,
      render: (_: any, record: any) => (
        <Space>
          <Button type="link" icon={<EyeOutlined />} onClick={() => openArtifact(record)}>
            预览
          </Button>
          <Button type="link" icon={<DownloadOutlined />} onClick={() => downloadArtifact(record)}>
            下载
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
        </Space>
        {items.length ? (
          <Table
            columns={columns}
            dataSource={items}
            rowKey="path"
            loading={loading}
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
            <Paragraph style={{ whiteSpace: 'pre-wrap', maxHeight: 560, overflow: 'auto' }}>
              {drawer.content || '无可预览文本'}
            </Paragraph>
          </Space>
        ) : null}
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
