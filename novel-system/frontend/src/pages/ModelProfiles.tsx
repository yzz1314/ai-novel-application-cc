import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  ApiOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  StarOutlined,
} from '@ant-design/icons';
import { modelProfileApi } from '../services/api';

const { Text, Title } = Typography;

const providerOptions = [
  { label: 'Mock', value: 'mock' },
  { label: 'OpenAI', value: 'openai' },
  { label: 'Anthropic', value: 'anthropic' },
  { label: 'Custom', value: 'custom' },
];

const ModelProfiles: React.FC = () => {
  const [profiles, setProfiles] = useState<any[]>([]);
  const [defaultProfile, setDefaultProfile] = useState<any>(null);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingProfile, setEditingProfile] = useState<any>(null);
  const [saving, setSaving] = useState(false);
  const [testResult, setTestResult] = useState<any>(null);
  const [form] = Form.useForm();

  useEffect(() => {
    loadProfiles();
  }, []);

  const defaultProfileId = defaultProfile?.profileId;

  const loadProfiles = async () => {
    try {
      setLoading(true);
      const [profileData, defaultData] = await Promise.all([
        modelProfileApi.getList().catch(() => []),
        modelProfileApi.getDefault().catch(() => null),
      ]);
      setProfiles(profileData || []);
      setDefaultProfile(defaultData);
    } catch (error) {
      message.error('加载模型配置失败');
    } finally {
      setLoading(false);
    }
  };

  const openCreate = () => {
    setEditingProfile(null);
    setTestResult(null);
    form.resetFields();
    form.setFieldsValue({
      enabled: true,
      setDefault: profiles.length === 0,
      mainProvider: 'mock',
      mainModel: 'mock-local',
      mainTemperature: 0.7,
      mainMaxTokens: 4000,
      mainTimeout: 60,
      mainMock: true,
      fastProvider: 'mock',
      fastModel: 'mock-local',
      fastTemperature: 0.3,
      fastMaxTokens: 2000,
      fastTimeout: 60,
      fastMock: true,
    });
    setDrawerOpen(true);
  };

  const openEdit = async (profile: any) => {
    try {
      const data = await modelProfileApi.get(profile.profileId);
      setEditingProfile(data);
      setTestResult(null);
      form.setFieldsValue(flattenProfile(data, data.profileId === defaultProfileId));
      setDrawerOpen(true);
    } catch (error) {
      message.error('加载模型配置详情失败');
    }
  };

  const saveProfile = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      const payload = buildPayload(values);
      if (editingProfile) {
        await modelProfileApi.update(editingProfile.profileId, payload);
      } else {
        await modelProfileApi.create(payload);
      }
      message.success('模型配置已保存');
      setDrawerOpen(false);
      setEditingProfile(null);
      await loadProfiles();
    } catch (error) {
      if (error instanceof Error) {
        message.error('保存模型配置失败');
      }
    } finally {
      setSaving(false);
    }
  };

  const setDefault = async (profile: any) => {
    try {
      await modelProfileApi.setDefault(profile.profileId);
      message.success('默认模型配置已更新');
      await loadProfiles();
    } catch (error) {
      message.error('设置默认模型失败');
    }
  };

  const deleteProfile = async (profile: any) => {
    try {
      await modelProfileApi.delete(profile.profileId);
      message.success('模型配置已删除');
      await loadProfiles();
    } catch (error) {
      message.error('删除模型配置失败');
    }
  };

  const testProfile = async (profile?: any) => {
    const target = profile || editingProfile;
    if (!target) return;
    try {
      const result = await modelProfileApi.test(target.profileId, { taskType: 'chapter_writing' });
      setTestResult(result);
      message.success(result.status === 'passed' ? '模型配置测试通过' : '模型配置存在未通过项');
    } catch (error) {
      message.error('测试模型配置失败');
    }
  };

  const columns = useMemo(() => [
    {
      title: '配置',
      key: 'profile',
      render: (_: any, record: any) => (
        <Space direction="vertical" size={0}>
          <Space>
            <Text strong>{record.profileName || record.profileId}</Text>
            {record.profileId === defaultProfileId && <Tag color="gold">默认</Tag>}
            {!record.enabled && <Tag>停用</Tag>}
          </Space>
          <Text type="secondary">{record.description || record.profileId}</Text>
        </Space>
      ),
    },
    {
      title: '主模型',
      key: 'mainModel',
      render: (_: any, record: any) => <ModelTag model={record.mainModel} />,
    },
    {
      title: '快速模型',
      key: 'fastModel',
      render: (_: any, record: any) => record.fastModel ? <ModelTag model={record.fastModel} /> : <Text type="secondary">回退主模型</Text>,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 190,
    },
    {
      title: '操作',
      key: 'action',
      width: 330,
      render: (_: any, record: any) => (
        <Space size="small" wrap>
          <Button type="link" icon={<EditOutlined />} onClick={() => openEdit(record)}>编辑</Button>
          <Button type="link" icon={<ApiOutlined />} onClick={() => testProfile(record)}>测试</Button>
          <Button
            type="link"
            icon={<StarOutlined />}
            disabled={record.profileId === defaultProfileId}
            onClick={() => setDefault(record)}
          >
            设默认
          </Button>
          <Popconfirm
            title="删除模型配置？"
            description="删除后会自动调整默认配置。"
            okText="删除"
            cancelText="取消"
            onConfirm={() => deleteProfile(record)}
          >
            <Button type="link" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ], [defaultProfileId]);

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card>
        <Space align="start" style={{ justifyContent: 'space-between', width: '100%' }}>
          <div>
            <Title level={3} style={{ marginBottom: 0 }}>模型配置</Title>
            <Text type="secondary">管理主模型、快速模型、默认配置和基础连通性测试。</Text>
          </div>
          <Space>
            <Button icon={<ReloadOutlined />} onClick={loadProfiles}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建配置</Button>
          </Space>
        </Space>
      </Card>

      <Alert
        type="info"
        showIcon
        message="当前实现已支持配置管理、默认配置、API Key 掩码和结构/连通性测试；真实调用路由、成本统计和限流策略仍在后续阶段。"
      />

      <Table
        loading={loading}
        rowKey="profileId"
        dataSource={profiles}
        columns={columns}
        pagination={false}
      />

      <Drawer
        title={editingProfile ? '编辑模型配置' : '新建模型配置'}
        width={760}
        open={drawerOpen}
        onClose={() => {
          setDrawerOpen(false);
          setEditingProfile(null);
          setTestResult(null);
        }}
        extra={
          <Space>
            {editingProfile && <Button icon={<ApiOutlined />} onClick={() => testProfile()}>测试</Button>}
            <Button type="primary" loading={saving} onClick={saveProfile}>保存</Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="profileId" label="配置ID" rules={[{ required: true, message: '配置ID不能为空' }]}>
                <Input disabled={!!editingProfile} placeholder="default_openai" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="profileName" label="配置名称" rules={[{ required: true, message: '配置名称不能为空' }]}>
                <Input placeholder="默认创作模型" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="description" label="描述">
            <Input placeholder="用途、成本或适用场景" />
          </Form.Item>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="enabled" label="启用" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="setDefault" label="设为默认" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Col>
          </Row>

          <Card size="small" title="主模型" style={{ marginBottom: 16 }}>
            <ModelFields prefix="main" />
          </Card>
          <Card size="small" title="快速模型">
            <ModelFields prefix="fast" />
          </Card>
        </Form>

        {testResult && (
          <Card size="small" title="测试结果" style={{ marginTop: 16 }}>
            <Descriptions column={1} size="small">
              <Descriptions.Item label="状态">
                <Tag color={testResult.status === 'passed' ? 'success' : 'error'}>{testResult.status}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="任务类型">{testResult.taskType}</Descriptions.Item>
              <Descriptions.Item label="选择模型">
                <ModelTag model={testResult.selectedModel} />
              </Descriptions.Item>
            </Descriptions>
            <Table
              size="small"
              rowKey="id"
              dataSource={testResult.checks || []}
              pagination={false}
              columns={[
                {
                  title: '检查',
                  dataIndex: 'id',
                  key: 'id',
                },
                {
                  title: '结果',
                  dataIndex: 'passed',
                  key: 'passed',
                  width: 90,
                  render: (passed: boolean) => (
                    <Tag icon={<CheckCircleOutlined />} color={passed ? 'success' : 'error'}>
                      {passed ? '通过' : '失败'}
                    </Tag>
                  ),
                },
                {
                  title: '说明',
                  dataIndex: 'message',
                  key: 'message',
                },
              ]}
            />
          </Card>
        )}
      </Drawer>
    </Space>
  );
};

const ModelFields: React.FC<{ prefix: string }> = ({ prefix }) => (
  <>
    <Row gutter={16}>
      <Col span={8}>
        <Form.Item name={`${prefix}Provider`} label="Provider" rules={[{ required: true, message: 'Provider不能为空' }]}>
          <Select options={providerOptions} />
        </Form.Item>
      </Col>
      <Col span={16}>
        <Form.Item name={`${prefix}Model`} label="模型名" rules={[{ required: true, message: '模型名不能为空' }]}>
          <Input placeholder="gpt-4o-mini / claude-3-haiku / mock-local" />
        </Form.Item>
      </Col>
    </Row>
    <Form.Item name={`${prefix}ApiKey`} label="API Key">
      <Input.Password placeholder="保存后列表仅展示掩码" />
    </Form.Item>
    <Form.Item name={`${prefix}Endpoint`} label="Endpoint">
      <Input placeholder="https://api.openai.com/v1 或自定义兼容端点" />
    </Form.Item>
    <Row gutter={16}>
      <Col span={8}>
        <Form.Item name={`${prefix}Temperature`} label="Temperature">
          <InputNumber min={0} max={2} step={0.1} style={{ width: '100%' }} />
        </Form.Item>
      </Col>
      <Col span={8}>
        <Form.Item name={`${prefix}MaxTokens`} label="Max Tokens">
          <InputNumber min={1} max={200000} style={{ width: '100%' }} />
        </Form.Item>
      </Col>
      <Col span={8}>
        <Form.Item name={`${prefix}Timeout`} label="Timeout">
          <InputNumber min={1} max={600} style={{ width: '100%' }} />
        </Form.Item>
      </Col>
    </Row>
    <Form.Item name={`${prefix}Mock`} label="Mock模式" valuePropName="checked">
      <Switch />
    </Form.Item>
  </>
);

const ModelTag: React.FC<{ model?: any }> = ({ model }) => {
  if (!model) return <Text type="secondary">未配置</Text>;
  return (
    <Space size={4} wrap>
      <Tag>{model.provider}</Tag>
      <Text>{model.model}</Text>
      {model.mock && <Tag color="blue">mock</Tag>}
      {model.hasApiKey && <Tag color="green">key</Tag>}
    </Space>
  );
};

const flattenProfile = (profile: any, isDefault: boolean) => ({
  profileId: profile.profileId,
  profileName: profile.profileName,
  description: profile.description,
  enabled: profile.enabled ?? true,
  setDefault: isDefault,
  ...flattenModel('main', profile.mainModel),
  ...flattenModel('fast', profile.fastModel || profile.mainModel),
});

const flattenModel = (prefix: string, model: any = {}) => ({
  [`${prefix}Provider`]: model.provider || 'mock',
  [`${prefix}Model`]: model.model || 'mock-local',
  [`${prefix}ApiKey`]: '',
  [`${prefix}Endpoint`]: model.endpoint || '',
  [`${prefix}Temperature`]: model.temperature ?? 0.7,
  [`${prefix}MaxTokens`]: model.maxTokens ?? 4000,
  [`${prefix}Timeout`]: model.timeout ?? 60,
  [`${prefix}Mock`]: model.mock ?? model.provider === 'mock',
});

const buildPayload = (values: any) => ({
  profileId: values.profileId,
  profileName: values.profileName,
  description: values.description,
  enabled: values.enabled,
  setDefault: values.setDefault,
  mainModel: buildModel(values, 'main'),
  fastModel: buildModel(values, 'fast'),
});

const buildModel = (values: any, prefix: string) => {
  const model: any = {
    provider: values[`${prefix}Provider`],
    model: values[`${prefix}Model`],
    endpoint: values[`${prefix}Endpoint`] || '',
    temperature: values[`${prefix}Temperature`] ?? 0.7,
    maxTokens: values[`${prefix}MaxTokens`] ?? 4000,
    timeout: values[`${prefix}Timeout`] ?? 60,
    mock: values[`${prefix}Mock`] ?? false,
  };
  if (values[`${prefix}ApiKey`]) {
    model.apiKey = values[`${prefix}ApiKey`];
  }
  return model;
};

export default ModelProfiles;
