import React, { useEffect, useMemo, useState } from 'react'
import {
  Button,
  Col,
  Descriptions,
  Form,
  Input,
  Popconfirm,
  Row,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  AuditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
} from '@ant-design/icons'
import { accessApi, projectApi } from '../services/api'

const { Title, Text } = Typography

const roleOptions = [
  { label: 'owner', value: 'owner' },
  { label: 'admin', value: 'admin' },
  { label: 'editor', value: 'editor' },
  { label: 'artifact_manager', value: 'artifact_manager' },
  { label: 'viewer', value: 'viewer' },
]

const AccessGovernance: React.FC = () => {
  const [loading, setLoading] = useState(false)
  const [me, setMe] = useState<any>(null)
  const [organizations, setOrganizations] = useState<any[]>([])
  const [organizationMembers, setOrganizationMembers] = useState<any[]>([])
  const [projectMembers, setProjectMembers] = useState<any[]>([])
  const [auditEvents, setAuditEvents] = useState<any[]>([])
  const [rolePolicies, setRolePolicies] = useState<any[]>([])
  const [selectedOrg, setSelectedOrg] = useState<string>()
  const [selectedProject, setSelectedProject] = useState<string>()
  const [projects, setProjects] = useState<any[]>([])
  const [orgForm] = Form.useForm()
  const [projectForm] = Form.useForm()
  const [policyForm] = Form.useForm()

  useEffect(() => {
    loadAccessData()
  }, [])

  useEffect(() => {
    if (selectedOrg) {
      loadOrganizationMembers(selectedOrg)
      loadAudit({ organizationId: selectedOrg })
    }
  }, [selectedOrg])

  useEffect(() => {
    if (selectedProject) {
      loadProjectMembers(selectedProject)
      loadAudit({ projectId: selectedProject })
    }
  }, [selectedProject])

  const currentOrganizationId = selectedOrg || me?.organizationId || organizations[0]?.id

  const loadAccessData = async () => {
    try {
      setLoading(true)
      const [meData, orgData, projectData, auditData, policyData] = await Promise.all([
        accessApi.getMe().catch(() => null),
        accessApi.getOrganizations().catch(() => []),
        projectApi.getList().catch(() => []),
        accessApi.getAudit({ limit: 100 }).catch(() => ({ items: [] })),
        accessApi.getRolePolicies().catch(() => []),
      ])
      setMe(meData)
      setOrganizations(orgData || [])
      setProjects(projectData || [])
      setAuditEvents(auditData?.items || [])
      setRolePolicies(policyData || [])
      const orgId = meData?.organizationId || orgData?.[0]?.id
      setSelectedOrg(orgId)
      if (orgId) {
        await loadOrganizationMembers(orgId)
      }
      const projectId = projectData?.[0]?.id
      setSelectedProject(projectId)
      if (projectId) {
        await loadProjectMembers(projectId)
      }
    } catch (error) {
      message.error('加载访问治理数据失败')
    } finally {
      setLoading(false)
    }
  }

  const loadOrganizationMembers = async (organizationId: string) => {
    const members = await accessApi.getOrganizationMembers(organizationId).catch(() => [])
    setOrganizationMembers(members || [])
  }

  const loadProjectMembers = async (projectId: string) => {
    const members = await projectApi.getMembers(projectId).catch(() => [])
    setProjectMembers(members || [])
  }

  const loadAudit = async (params?: any) => {
    const data = await accessApi.getAudit({ limit: 100, ...(params || {}) }).catch(() => ({ items: [] }))
    setAuditEvents(data?.items || [])
  }

  const grantOrganizationMember = async () => {
    if (!currentOrganizationId) return
    const values = await orgForm.validateFields()
    await accessApi.grantOrganizationMember(currentOrganizationId, values)
    message.success('组织成员已授权')
    orgForm.resetFields()
    await loadOrganizationMembers(currentOrganizationId)
    await loadAudit({ organizationId: currentOrganizationId })
  }

  const revokeOrganizationMember = async (record: any) => {
    await accessApi.revokeOrganizationMember(record.organizationId, record.userId)
    message.success('组织成员已撤销')
    await loadOrganizationMembers(record.organizationId)
    await loadAudit({ organizationId: record.organizationId })
  }

  const grantProjectMember = async () => {
    if (!selectedProject) return
    const values = await projectForm.validateFields()
    await projectApi.grantMember(selectedProject, values)
    message.success('项目成员已授权')
    projectForm.resetFields()
    await loadProjectMembers(selectedProject)
    await loadAudit({ projectId: selectedProject })
  }

  const revokeProjectMember = async (record: any) => {
    await projectApi.revokeMember(record.projectId, record.userId)
    message.success('项目成员已撤销')
    await loadProjectMembers(record.projectId)
    await loadAudit({ projectId: record.projectId })
  }

  const updateRolePolicy = async () => {
    const values = await policyForm.validateFields()
    await accessApi.updateRolePolicy(values.actionKey, {
      allowedRoles: values.allowedRoles || [],
      description: values.description,
    })
    message.success('角色策略已更新')
    policyForm.resetFields()
    const policies = await accessApi.getRolePolicies().catch(() => [])
    setRolePolicies(policies || [])
    await loadAudit({ limit: 100 })
  }

  const orgOptions = useMemo(
    () => organizations.map((item) => ({ label: item.name || item.id, value: item.id })),
    [organizations]
  )

  const projectOptions = useMemo(
    () => projects.map((item) => ({ label: item.name || item.id, value: item.id })),
    [projects]
  )

  const memberColumns = [
    { title: '用户', dataIndex: 'userId', key: 'userId' },
    { title: '名称', dataIndex: 'displayName', key: 'displayName', render: (value: any) => value || '-' },
    { title: '角色', dataIndex: 'role', key: 'role', render: (value: string) => <Tag color="blue">{value}</Tag> },
    { title: '状态', dataIndex: 'status', key: 'status', render: (value: string) => <Tag>{value}</Tag> },
    { title: '授权人', dataIndex: 'grantedBy', key: 'grantedBy', render: (value: any) => value || '-' },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', render: (value: any) => value || '-' },
    {
      title: '操作',
      key: 'actions',
      render: (_: any, record: any) => (
        <Popconfirm title="撤销该成员授权？" onConfirm={() => revokeOrganizationMember(record)}>
          <Button danger size="small">撤销</Button>
        </Popconfirm>
      ),
    },
  ]

  const projectMemberColumns = [
    { title: '用户', dataIndex: 'userId', key: 'userId' },
    { title: '名称', dataIndex: 'actor', key: 'actor', render: (value: any) => value || '-' },
    { title: '组织', dataIndex: 'organizationId', key: 'organizationId', render: (value: any) => value || '-' },
    { title: '角色', dataIndex: 'role', key: 'role', render: (value: string) => <Tag color="geekblue">{value}</Tag> },
    { title: '状态', dataIndex: 'status', key: 'status', render: (value: string) => <Tag>{value}</Tag> },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', render: (value: any) => value || '-' },
    {
      title: '操作',
      key: 'actions',
      render: (_: any, record: any) => (
        <Popconfirm title="撤销该项目成员授权？" onConfirm={() => revokeProjectMember(record)}>
          <Button danger size="small">撤销</Button>
        </Popconfirm>
      ),
    },
  ]

  const auditColumns = [
    { title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 190, render: (value: any) => value || '-' },
    { title: '事件', dataIndex: 'eventType', key: 'eventType', render: (value: string) => <Tag color="purple">{value}</Tag> },
    { title: '操作者', dataIndex: 'actorName', key: 'actorName', render: (_: any, record: any) => record.actorName || record.actorId || '-' },
    { title: '组织', dataIndex: 'organizationId', key: 'organizationId', render: (value: any) => value || '-' },
    { title: '项目', dataIndex: 'projectId', key: 'projectId', render: (value: any) => value || '-' },
    { title: '目标用户', dataIndex: 'targetUserId', key: 'targetUserId', render: (value: any) => value || '-' },
    { title: '结果', dataIndex: 'outcome', key: 'outcome', render: (value: string) => <Tag color={value === 'success' ? 'green' : 'red'}>{value}</Tag> },
    { title: '说明', dataIndex: 'reason', key: 'reason', render: (value: any) => value || '-' },
  ]

  const rolePolicyColumns = [
    { title: '动作', dataIndex: 'actionKey', key: 'actionKey' },
    { title: '说明', dataIndex: 'description', key: 'description', render: (value: any) => value || '-' },
    {
      title: '允许角色',
      dataIndex: 'allowedRoles',
      key: 'allowedRoles',
      render: (roles: string[] = []) => roles.map((role) => <Tag key={role} color="cyan">{role}</Tag>),
    },
    { title: '来源', dataIndex: 'default', key: 'default', render: (value: boolean) => <Tag>{value ? '默认' : '已配置'}</Tag> },
    { title: '更新人', dataIndex: 'updatedBy', key: 'updatedBy', render: (value: any) => value || '-' },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', render: (value: any) => value || '-' },
  ]

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Row justify="space-between" align="middle">
        <Col>
          <Title level={2} style={{ marginBottom: 4 }}>访问治理</Title>
          <Text type="secondary">用户、组织、项目成员与访问审计</Text>
        </Col>
        <Col>
          <Button icon={<ReloadOutlined />} onClick={loadAccessData} loading={loading}>
            刷新
          </Button>
        </Col>
      </Row>

      <Descriptions bordered size="small" column={3}>
        <Descriptions.Item label="当前用户">{me?.userId || '-'}</Descriptions.Item>
        <Descriptions.Item label="显示名">{me?.actor || '-'}</Descriptions.Item>
        <Descriptions.Item label="组织">{me?.organizationId || '-'}</Descriptions.Item>
        <Descriptions.Item label="请求角色">
          {(me?.roles || []).map((role: string) => <Tag key={role}>{role}</Tag>)}
        </Descriptions.Item>
        <Descriptions.Item label="组织成员关系">{me?.organizationMemberships?.length ?? 0}</Descriptions.Item>
        <Descriptions.Item label="项目成员关系">{me?.projectMemberships?.length ?? 0}</Descriptions.Item>
      </Descriptions>

      <Tabs
        items={[
          {
            key: 'organizations',
            label: <span><TeamOutlined />组织成员</span>,
            children: (
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                <Row gutter={12}>
                  <Col flex="260px">
                    <Select
                      style={{ width: '100%' }}
                      value={currentOrganizationId}
                      options={orgOptions}
                      onChange={setSelectedOrg}
                      placeholder="选择组织"
                    />
                  </Col>
                </Row>
                <Form form={orgForm} layout="inline">
                  <Form.Item name="userId" rules={[{ required: true, message: '请输入用户ID' }]}>
                    <Input placeholder="用户ID" />
                  </Form.Item>
                  <Form.Item name="displayName">
                    <Input placeholder="显示名" />
                  </Form.Item>
                  <Form.Item name="role" initialValue="viewer">
                    <Select style={{ width: 180 }} options={roleOptions} />
                  </Form.Item>
                  <Form.Item>
                    <Button type="primary" icon={<PlusOutlined />} onClick={grantOrganizationMember}>
                      授权组织成员
                    </Button>
                  </Form.Item>
                </Form>
                <Table
                  rowKey="id"
                  loading={loading}
                  columns={memberColumns}
                  dataSource={organizationMembers}
                  pagination={{ pageSize: 8 }}
                />
              </Space>
            ),
          },
          {
            key: 'projects',
            label: <span><SafetyCertificateOutlined />项目成员</span>,
            children: (
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                <Row gutter={12}>
                  <Col flex="320px">
                    <Select
                      style={{ width: '100%' }}
                      value={selectedProject}
                      options={projectOptions}
                      onChange={setSelectedProject}
                      placeholder="选择项目"
                    />
                  </Col>
                </Row>
                <Form form={projectForm} layout="inline">
                  <Form.Item name="userId" rules={[{ required: true, message: '请输入用户ID' }]}>
                    <Input placeholder="用户ID" />
                  </Form.Item>
                  <Form.Item name="actor">
                    <Input placeholder="显示名" />
                  </Form.Item>
                  <Form.Item name="organizationId" initialValue={currentOrganizationId}>
                    <Input placeholder="组织ID" />
                  </Form.Item>
                  <Form.Item name="role" initialValue="viewer">
                    <Select style={{ width: 180 }} options={roleOptions} />
                  </Form.Item>
                  <Form.Item>
                    <Button type="primary" icon={<PlusOutlined />} onClick={grantProjectMember}>
                      授权项目成员
                    </Button>
                  </Form.Item>
                </Form>
                <Table
                  rowKey="id"
                  loading={loading}
                  columns={projectMemberColumns}
                  dataSource={projectMembers}
                  pagination={{ pageSize: 8 }}
                />
              </Space>
            ),
          },
          {
            key: 'policies',
            label: <span><SafetyCertificateOutlined />角色策略</span>,
            children: (
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                <Form form={policyForm} layout="inline">
                  <Form.Item name="actionKey" rules={[{ required: true, message: '请选择动作' }]}>
                    <Select
                      style={{ width: 240 }}
                      placeholder="动作"
                      options={[
                        { label: 'access_governance', value: 'access_governance' },
                        { label: 'project_mutation', value: 'project_mutation' },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item name="allowedRoles" rules={[{ required: true, message: '请选择允许角色' }]}>
                    <Select
                      mode="multiple"
                      style={{ width: 360 }}
                      placeholder="允许角色"
                      options={roleOptions}
                    />
                  </Form.Item>
                  <Form.Item name="description">
                    <Input placeholder="说明" style={{ width: 260 }} />
                  </Form.Item>
                  <Form.Item>
                    <Button type="primary" icon={<PlusOutlined />} onClick={updateRolePolicy}>
                      保存策略
                    </Button>
                  </Form.Item>
                </Form>
                <Table
                  rowKey="actionKey"
                  columns={rolePolicyColumns}
                  dataSource={rolePolicies}
                  pagination={false}
                />
              </Space>
            ),
          },
          {
            key: 'audit',
            label: <span><AuditOutlined />访问审计</span>,
            children: (
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                <Space>
                  <Button onClick={() => loadAudit({ limit: 100 })}>全部</Button>
                  <Button onClick={() => currentOrganizationId && loadAudit({ organizationId: currentOrganizationId })}>当前组织</Button>
                  <Button onClick={() => selectedProject && loadAudit({ projectId: selectedProject })}>当前项目</Button>
                  <Button onClick={() => me?.userId && loadAudit({ actorId: me.userId })}>我的操作</Button>
                </Space>
                <Table
                  rowKey="id"
                  columns={auditColumns}
                  dataSource={auditEvents}
                  pagination={{ pageSize: 12 }}
                  scroll={{ x: 1100 }}
                />
              </Space>
            ),
          },
        ]}
      />
    </Space>
  )
}

export default AccessGovernance
