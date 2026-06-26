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

const pickArray = (...values: any[]) => {
  for (const value of values) {
    if (Array.isArray(value)) return value
  }
  return []
}

const GraphView: React.FC = () => {
  const { projectId } = useParams<{ projectId: string }>()
  const [loading, setLoading] = useState(false)
  const [building, setBuilding] = useState(false)
  const [syncing, setSyncing] = useState(false)
  const [pathLoading, setPathLoading] = useState(false)
  const [books, setBooks] = useState<any[]>([])
  const [bookId, setBookId] = useState('default')
  const [graph, setGraph] = useState<any>(null)
  const [lastTask, setLastTask] = useState<any>(null)
  const [sourceNode, setSourceNode] = useState<string>()
  const [targetNode, setTargetNode] = useState<string>()
  const [pathResult, setPathResult] = useState<any>(null)

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
    const graphStats = graph?.statistics || {}
    return {
      totalNodes: graphStats.totalNodes ?? graphStats.total_nodes ?? nodes.length,
      totalEdges: graphStats.totalEdges ?? graphStats.total_edges ?? edges.length,
      characterCount: nodes.filter((node: any) => node.node_type === 'character').length,
      organizationCount: nodes.filter((node: any) => node.node_type === 'organization').length,
      connectedComponents: graphStats.connectedComponents ?? graphStats.connected_components ?? 0,
      largestComponentSize: graphStats.largestComponentSize ?? graphStats.largest_component_size ?? 0,
      averageDegree: graphStats.averageDegree ?? graphStats.average_degree ?? 0,
      density: graphStats.density ?? 0,
    }
  }, [graph])

  const advanced = useMemo(() => {
    const graphStats = graph?.statistics || {}
    const sourceStats = graphStats.sourceStatistics || {}
    const analysis = graph?.analysis || {}
    return {
      topCentrality: pickArray(
        graphStats.topNodesByCentrality,
        graphStats.top_nodes_by_centrality,
        sourceStats.top_nodes_by_centrality
      ),
      topBetweenness: pickArray(
        graphStats.topNodesByBetweenness,
        graphStats.top_nodes_by_betweenness,
        sourceStats.top_nodes_by_betweenness
      ),
      bridgeNodes: pickArray(analysis.bridgeNodes, analysis.bridge_nodes),
      isolatedNodes: pickArray(analysis.isolatedNodes, analysis.isolated_nodes, graphStats.isolatedNodes),
      relationshipAnalysis: pickArray(analysis.relationshipAnalysis, analysis.relationship_analysis),
      keyPaths: pickArray(analysis.keyPaths, analysis.key_paths),
      componentSummary: pickArray(analysis.componentSummary, analysis.component_summary),
      warnings: pickArray(analysis.warnings),
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

  const nodeNameById = useMemo(() => {
    const map = new Map<string, string>()
    ;(graph?.nodes || []).forEach((node: any) => {
      map.set(node.node_id, node.name || node.node_id)
    })
    return map
  }, [graph])

  const nodeOptions = useMemo(
    () =>
      (graph?.nodes || []).map((node: any) => ({
        label: `${node.name || node.node_id} (${node.node_type})`,
        value: node.node_id,
      })),
    [graph]
  )

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
    if (!projectId) return
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

  const queryShortestPath = async () => {
    if (!projectId || !sourceNode || !targetNode) return
    if (sourceNode === targetNode) {
      message.warning('请选择两个不同节点')
      return
    }
    try {
      setPathLoading(true)
      const result = await graphApi.query(projectId, bookId || 'default', {
        queryType: 'path',
        sourceNode,
        targetNode,
        maxDepth: 5,
      })
      setPathResult(result)
      if (!result?.paths?.length) {
        message.info('未找到 5 跳以内路径')
      }
    } catch (error) {
      message.error('路径查询失败')
    } finally {
      setPathLoading(false)
    }
  }

  const getNodeId = (record: any) => record?.node_id || record?.nodeId
  const getNodeType = (record: any) => record?.node_type || record?.nodeType
  const nodeLabel = (value: any) => {
    if (!value) return '-'
    if (typeof value === 'object') return value.name || value.node_id || value.nodeId || '-'
    return nodeNameById.get(String(value)) || String(value)
  }

  const renderNodeTag = (record: any) => (
    <Space>
      <Tag color={nodeColors[getNodeType(record)] || 'default'}>{getNodeType(record) || 'node'}</Tag>
      <strong>{record?.name || getNodeId(record)}</strong>
    </Space>
  )

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

  const centralityColumns = [
    {
      title: '节点',
      key: 'node',
      render: (_: any, record: any) => renderNodeTag(record),
    },
    {
      title: '度中心度',
      key: 'degreeCentrality',
      width: 120,
      render: (_: any, record: any) =>
        Number(record.degreeCentrality ?? record.degree_centrality ?? 0).toFixed(3),
    },
    {
      title: '度数',
      dataIndex: 'degree',
      key: 'degree',
      width: 80,
    },
  ]

  const betweennessColumns = [
    {
      title: '节点',
      key: 'node',
      render: (_: any, record: any) => renderNodeTag(record),
    },
    {
      title: '桥接中心度',
      key: 'betweennessCentrality',
      width: 130,
      render: (_: any, record: any) =>
        Number(record.betweennessCentrality ?? record.betweenness_centrality ?? 0).toFixed(3),
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
            <Row gutter={16}>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic title="连通分量" value={stats.connectedComponents} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic title="最大分量节点" value={stats.largestComponentSize} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic title="平均度" value={Number(stats.averageDegree).toFixed(2)} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic title="密度" value={Number(stats.density).toFixed(4)} />
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

            <Card size="small" title="高级分析">
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                {advanced.warnings.map((warning: any) => (
                  <Alert
                    key={warning.code || warning.message}
                    type={warning.severity === 'warning' ? 'warning' : 'info'}
                    showIcon
                    message={warning.message || warning.code}
                  />
                ))}
                <Row gutter={16}>
                  <Col xs={24} lg={12}>
                    <Card size="small" title="中心节点">
                      <Table
                        rowKey={(record: any) => getNodeId(record)}
                        size="small"
                        columns={centralityColumns}
                        dataSource={advanced.topCentrality}
                        pagination={false}
                        locale={{ emptyText: '暂无中心度数据' }}
                      />
                    </Card>
                  </Col>
                  <Col xs={24} lg={12}>
                    <Card size="small" title="桥接节点">
                      <Table
                        rowKey={(record: any) => getNodeId(record)}
                        size="small"
                        columns={betweennessColumns}
                        dataSource={advanced.bridgeNodes.length ? advanced.bridgeNodes : advanced.topBetweenness}
                        pagination={false}
                        locale={{ emptyText: '暂无桥接节点' }}
                      />
                    </Card>
                  </Col>
                </Row>
                <Row gutter={16}>
                  <Col xs={24} lg={12}>
                    <Card size="small" title="连通分量">
                      <Table
                        rowKey={(record: any, index) => record.component_id || record.componentId || index}
                        size="small"
                        dataSource={advanced.componentSummary}
                        pagination={false}
                        columns={[
                          {
                            title: '规模',
                            dataIndex: 'size',
                            key: 'size',
                            width: 90,
                          },
                          {
                            title: '代表节点',
                            key: 'nodes',
                            render: (_: any, record: any) => (
                              <Space wrap>
                                {(record.nodes || []).slice(0, 6).map((node: any) => (
                                  <Tag key={getNodeId(node)}>{nodeLabel(node)}</Tag>
                                ))}
                              </Space>
                            ),
                          },
                        ]}
                        locale={{ emptyText: '暂无连通分量数据' }}
                      />
                    </Card>
                  </Col>
                  <Col xs={24} lg={12}>
                    <Card size="small" title="孤立节点">
                      {advanced.isolatedNodes.length ? (
                        <Space wrap>
                          {advanced.isolatedNodes.slice(0, 20).map((node: any) => (
                            <Tag key={getNodeId(node)} color={nodeColors[getNodeType(node)] || 'default'}>
                              {nodeLabel(node)}
                            </Tag>
                          ))}
                        </Space>
                      ) : (
                        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无孤立节点" />
                      )}
                    </Card>
                  </Col>
                </Row>
                <Card size="small" title="最短路径查询">
                  <Space direction="vertical" style={{ width: '100%' }}>
                    <Space wrap>
                      <Select
                        showSearch
                        allowClear
                        style={{ minWidth: 260 }}
                        placeholder="起始节点"
                        value={sourceNode}
                        onChange={setSourceNode}
                        options={nodeOptions}
                        optionFilterProp="label"
                      />
                      <Select
                        showSearch
                        allowClear
                        style={{ minWidth: 260 }}
                        placeholder="目标节点"
                        value={targetNode}
                        onChange={setTargetNode}
                        options={nodeOptions}
                        optionFilterProp="label"
                      />
                      <Button
                        type="primary"
                        onClick={queryShortestPath}
                        loading={pathLoading}
                        disabled={!sourceNode || !targetNode}
                      >
                        查询路径
                      </Button>
                    </Space>
                    {pathResult?.paths?.length ? (
                      <Space wrap>
                        {pathResult.paths[0].map((nodeId: string, index: number) => (
                          <React.Fragment key={`${nodeId}-${index}`}>
                            <Tag color="blue">{nodeLabel(nodeId)}</Tag>
                            {index < pathResult.paths[0].length - 1 && <Text type="secondary">→</Text>}
                          </React.Fragment>
                        ))}
                      </Space>
                    ) : (
                      advanced.keyPaths.length > 0 && (
                        <Space direction="vertical" size={4}>
                          <Text type="secondary">推荐关注路径</Text>
                          {advanced.keyPaths.slice(0, 3).map((item: any, index: number) => (
                            <Text key={index}>
                              {nodeLabel(item.source)} → {nodeLabel(item.target)}，{item.length} 跳
                            </Text>
                          ))}
                        </Space>
                      )
                    )}
                  </Space>
                </Card>
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
