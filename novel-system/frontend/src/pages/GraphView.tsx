import React, { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
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
  DatabaseOutlined,
  DownloadOutlined,
  PartitionOutlined,
  ReloadOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { useParams } from 'react-router-dom'
import { bookApi, graphApi, taskApi } from '../services/api'

const { Text } = Typography

const nodeColors: Record<string, string> = {
  character: 'blue',
  location: 'green',
  organization: 'purple',
  item: 'gold',
  skill: 'cyan',
  plot: 'magenta',
  foreshadowing: 'volcano',
  event: 'orange',
  rule: 'red',
}

const GraphView: React.FC = () => {
  const { projectId } = useParams<{ projectId: string }>()
  const [loading, setLoading] = useState(false)
  const [building, setBuilding] = useState(false)
  const [syncing, setSyncing] = useState(false)
  const [books, setBooks] = useState<any[]>([])
  const [bookId, setBookId] = useState('default')
  const [graph, setGraph] = useState<any>(null)
  const [lastTask, setLastTask] = useState<any>(null)

  useEffect(() => {
    if (!projectId) return
    loadBooks()
  }, [projectId])

  useEffect(() => {
    if (!projectId || !bookId) return
    loadGraph()
  }, [projectId, bookId])

  const stats = useMemo(() => {
    const nodes = graph?.nodes || []
    const edges = graph?.edges || []
    return {
      totalNodes: graph?.statistics?.totalNodes ?? nodes.length,
      totalEdges: graph?.statistics?.totalEdges ?? edges.length,
      characterCount: nodes.filter((node: any) => node.node_type === 'character').length,
      organizationCount: nodes.filter((node: any) => node.node_type === 'organization').length,
    }
  }, [graph])

  const layoutNodes = useMemo(() => {
    const nodes = graph?.nodes || []
    const width = 720
    const height = 320
    const radius = Math.min(width, height) / 2 - 38
    return nodes.slice(0, 24).map((node: any, index: number) => {
      const angle = (Math.PI * 2 * index) / Math.max(nodes.length, 1)
      return {
        ...node,
        x: width / 2 + Math.cos(angle) * radius,
        y: height / 2 + Math.sin(angle) * radius,
      }
    })
  }, [graph])

  const nodePosition = useMemo(() => {
    const map = new Map<string, any>()
    layoutNodes.forEach((node: any) => map.set(node.node_id, node))
    return map
  }, [layoutNodes])

  const loadBooks = async () => {
    if (!projectId) return
    try {
      const data = await bookApi.getList(projectId)
      setBooks(data)
      if (data?.[0]?.bookId) {
        setBookId(data[0].bookId)
      }
    } catch (error) {
      message.error('加载书籍失败')
    }
  }

  const loadGraph = async () => {
    if (!projectId) return
    try {
      setLoading(true)
      const data = await graphApi.get(projectId, bookId || 'default')
      setGraph(data)
    } catch (error) {
      setGraph(null)
    } finally {
      setLoading(false)
    }
  }

  const pollTask = async (taskId: string) => {
    if (!projectId) return
    for (let i = 0; i < 20; i += 1) {
      await new Promise((resolve) => setTimeout(resolve, 1500))
      const task = await taskApi.getStatus(projectId, taskId)
      setLastTask(task)
      if (['SUCCESS', 'FAILED', 'CANCELLED'].includes(task.status)) {
        setBuilding(false)
        if (task.status === 'SUCCESS') {
          message.success('图谱构建完成')
          await loadGraph()
        } else {
          message.error('图谱构建失败')
        }
        return
      }
    }
    setBuilding(false)
  }

  const handleBuild = async () => {
    if (!projectId) return
    try {
      setBuilding(true)
      const task: any = await graphApi.build(projectId, {
        book_id: bookId || 'default',
        use_memory_data: true,
        use_outline_data: true,
        include_characters: true,
        include_locations: true,
        include_organizations: true,
        include_items: true,
        include_skills: true,
      })
      setLastTask(task)
      message.success('图谱构建任务已启动')
      pollTask(task.id)
    } catch (error) {
      setBuilding(false)
      message.error('构建图谱失败')
    }
  }

  const handleExport = async (format: string) => {
    try {
      const blob = await graphApi.export(projectId, bookId || 'default', format)
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `knowledge_graph.${format}`
      link.click()
      window.URL.revokeObjectURL(url)
      message.success('导出成功')
    } catch (error) {
      message.error('导出失败')
    }
  }

  const syncGraphDb = async () => {
    if (!projectId) return
    try {
      setSyncing(true)
      message.loading({ content: '正在同步图谱数据库', key: 'graph-sync' })
      await graphApi.syncDb(projectId, bookId || 'default', { book_id: bookId || 'default' })
      message.success({ content: '图谱数据库已同步', key: 'graph-sync' })
    } catch (error) {
      message.error({ content: '同步图谱数据库失败', key: 'graph-sync' })
    } finally {
      setSyncing(false)
    }
  }

  const nodeColumns = [
    {
      title: '节点',
      dataIndex: 'name',
      key: 'name',
      render: (name: string, record: any) => (
        <Space>
          <Tag color={nodeColors[record.node_type] || 'default'}>{record.node_type}</Tag>
          <strong>{name || record.node_id}</strong>
        </Space>
      ),
    },
    {
      title: '首次提及',
      dataIndex: 'first_mentioned',
      key: 'first_mentioned',
      width: 100,
      render: (value: number) => `第${value || 0}章`,
    },
    {
      title: '最后更新',
      dataIndex: 'last_updated',
      key: 'last_updated',
      width: 100,
      render: (value: number) => `第${value || 0}章`,
    },
  ]

  const edgeColumns = [
    {
      title: '关系',
      dataIndex: 'edge_type',
      key: 'edge_type',
      render: (value: string) => <Tag color="geekblue">{value}</Tag>,
    },
    {
      title: '源节点',
      dataIndex: 'source_id',
      key: 'source_id',
      ellipsis: true,
    },
    {
      title: '目标节点',
      dataIndex: 'target_id',
      key: 'target_id',
      ellipsis: true,
    },
  ]

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card
        title={
          <Space>
            <PartitionOutlined />
            知识图谱
          </Space>
        }
        extra={
          <Space wrap>
            <Select
              style={{ minWidth: 220 }}
              value={bookId}
              onChange={setBookId}
              options={
                books.length
                  ? books.map((book) => ({ label: book.bookTitle || book.bookId, value: book.bookId }))
                  : [{ label: '默认书籍', value: 'default' }]
              }
            />
            <Button icon={<ReloadOutlined />} onClick={loadGraph} loading={loading}>
              刷新
            </Button>
            <Button icon={<DatabaseOutlined />} onClick={syncGraphDb} loading={syncing} disabled={!graph}>
              同步数据库
            </Button>
            <Button type="primary" icon={<PartitionOutlined />} onClick={handleBuild} loading={building}>
              {graph ? '重建图谱' : '构建图谱'}
            </Button>
            <Select
              style={{ width: 120 }}
              defaultValue="json"
              onChange={handleExport}
              disabled={!graph}
              options={[
                { label: '导出 JSON', value: 'json' },
                { label: '导出 GraphML', value: 'graphml' },
                { label: '导出 HTML', value: 'html' },
              ]}
              suffixIcon={<DownloadOutlined />}
            />
          </Space>
        }
      >
        {lastTask && (
          <Alert
            style={{ marginBottom: 16 }}
            type={lastTask.status === 'FAILED' ? 'error' : lastTask.status === 'SUCCESS' ? 'success' : 'info'}
            showIcon
            message={`最近图谱任务：${lastTask.id} / ${lastTask.status}`}
          />
        )}

        {graph ? (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <Row gutter={16}>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic title="节点总数" value={stats.totalNodes} prefix={<PartitionOutlined />} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic title="关系总数" value={stats.totalEdges} prefix={<TeamOutlined />} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic title="人物节点" value={stats.characterCount} prefix={<UserOutlined />} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic title="组织节点" value={stats.organizationCount} />
                </Card>
              </Col>
            </Row>

            <Card size="small" title="图谱概览">
              <Space direction="vertical" style={{ width: '100%' }}>
                <Text type="secondary">
                  {graph.path} · 图谱ID {graph.graph_id} · 更新时间 {graph.updatedAt || graph.updated_at}
                </Text>
                <svg width="100%" height="340" viewBox="0 0 720 320" role="img">
                  {(graph.edges || []).slice(0, 40).map((edge: any) => {
                    const source = nodePosition.get(edge.source_id)
                    const target = nodePosition.get(edge.target_id)
                    if (!source || !target) return null
                    return (
                      <line
                        key={edge.edge_id}
                        x1={source.x}
                        y1={source.y}
                        x2={target.x}
                        y2={target.y}
                        stroke="#bfbfbf"
                        strokeWidth="1.5"
                      />
                    )
                  })}
                  {layoutNodes.map((node: any) => (
                    <g key={node.node_id}>
                      <circle cx={node.x} cy={node.y} r="18" fill="#fff" stroke="#1677ff" strokeWidth="2" />
                      <text x={node.x} y={node.y + 34} textAnchor="middle" fontSize="12" fill="#333">
                        {String(node.name || node.node_id).slice(0, 10)}
                      </text>
                    </g>
                  ))}
                </svg>
              </Space>
            </Card>

            <Row gutter={16}>
              <Col xs={24} lg={12}>
                <Card size="small" title="节点列表">
                  <Table
                    rowKey="node_id"
                    size="small"
                    columns={nodeColumns}
                    dataSource={graph.nodes || []}
                    pagination={{ pageSize: 8 }}
                  />
                </Card>
              </Col>
              <Col xs={24} lg={12}>
                <Card size="small" title="关系列表">
                  <Table
                    rowKey="edge_id"
                    size="small"
                    columns={edgeColumns}
                    dataSource={graph.edges || []}
                    pagination={{ pageSize: 8 }}
                  />
                </Card>
              </Col>
            </Row>
          </Space>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无图谱数据">
            <Button type="primary" onClick={handleBuild} loading={building}>
              构建图谱
            </Button>
          </Empty>
        )}
      </Card>
    </Space>
  )
}

export default GraphView
