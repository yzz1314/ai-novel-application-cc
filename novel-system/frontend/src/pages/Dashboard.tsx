import React, { useEffect, useState } from 'react';
import { Card, Row, Col, Statistic, Typography, Button, List, Tag } from 'antd';
import {
  ProjectOutlined,
  FileTextOutlined,
  EditOutlined,
  RocketOutlined,
  ArrowRightOutlined
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';

const { Title, Paragraph } = Typography;

const Dashboard: React.FC = () => {
  const navigate = useNavigate();
  const [stats, setStats] = useState({
    totalProjects: 0,
    totalSamples: 0,
    totalChapters: 0,
    activeProjects: 0
  });

  const quickActions = [
    {
      title: '创建新项目',
      description: '开始一个新的小说创作项目',
      icon: <ProjectOutlined style={{ fontSize: 24, color: '#1890ff' }} />,
      action: () => navigate('/projects?action=create')
    },
    {
      title: '上传样本',
      description: '上传样本小说进行分析',
      icon: <FileTextOutlined style={{ fontSize: 24, color: '#52c41a' }} />,
      action: () => navigate('/projects')
    },
    {
      title: '继续创作',
      description: '继续编辑你的小说',
      icon: <EditOutlined style={{ fontSize: 24, color: '#faad14' }} />,
      action: () => navigate('/projects')
    }
  ];

  const recentProjects = [
    {
      id: '1',
      name: '示例项目',
      status: 'active',
      updatedAt: '2026-06-24'
    }
  ];

  const workflowSteps = [
    { title: '1. 创建项目', description: '设置项目基本信息' },
    { title: '2. 上传样本', description: '上传2-3本同类型样本小说' },
    { title: '3. 分析生成Skills', description: '自动分析并生成创作指导' },
    { title: '4. 规划大纲', description: '生成完整的书籍大纲' },
    { title: '5. 创作正文', description: '基于大纲生成章节正文' }
  ];

  return (
    <div>
      <div style={{ marginBottom: 24 }}>
        <Title level={2}>工作台</Title>
        <Paragraph type="secondary">
          欢迎使用AI小说创作系统，从样本分析到正文生成的完整创作流程
        </Paragraph>
      </div>

      {/* 统计卡片 */}
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card>
            <Statistic
              title="项目总数"
              value={stats.totalProjects}
              prefix={<ProjectOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="样本总数"
              value={stats.totalSamples}
              prefix={<FileTextOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="章节总数"
              value={stats.totalChapters}
              prefix={<EditOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="活跃项目"
              value={stats.activeProjects}
              prefix={<RocketOutlined />}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16}>
        {/* 快速操作 */}
        <Col span={16}>
          <Card title="快速操作" style={{ marginBottom: 16 }}>
            <Row gutter={16}>
              {quickActions.map((action, index) => (
                <Col span={8} key={index}>
                  <Card
                    hoverable
                    style={{ textAlign: 'center', height: 180 }}
                    onClick={action.action}
                  >
                    <div style={{ marginBottom: 16 }}>{action.icon}</div>
                    <Title level={5}>{action.title}</Title>
                    <Paragraph type="secondary" style={{ fontSize: 12 }}>
                      {action.description}
                    </Paragraph>
                  </Card>
                </Col>
              ))}
            </Row>
          </Card>

          {/* 创作流程 */}
          <Card title="创作流程">
            <List
              dataSource={workflowSteps}
              renderItem={(item) => (
                <List.Item>
                  <List.Item.Meta
                    title={item.title}
                    description={item.description}
                  />
                  <ArrowRightOutlined style={{ color: '#d9d9d9' }} />
                </List.Item>
              )}
            />
          </Card>
        </Col>

        {/* 最近项目 */}
        <Col span={8}>
          <Card
            title="最近项目"
            extra={<Button type="link" onClick={() => navigate('/projects')}>查看全部</Button>}
          >
            <List
              dataSource={recentProjects}
              renderItem={(project) => (
                <List.Item>
                  <List.Item.Meta
                    title={
                      <a onClick={() => navigate(`/projects/${project.id}`)}>
                        {project.name}
                      </a>
                    }
                    description={`更新时间: ${project.updatedAt}`}
                  />
                  <Tag color="green">活跃</Tag>
                </List.Item>
              )}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default Dashboard;
