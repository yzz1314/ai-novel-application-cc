import React from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu, Typography } from 'antd';
import {
  ApiOutlined,
  DashboardOutlined,
  ProjectOutlined,
  SafetyCertificateOutlined,
  ScheduleOutlined,
} from '@ant-design/icons';

const { Header, Sider, Content } = Layout;
const { Title } = Typography;

const MainLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();

  const menuItems = [
    {
      key: '/dashboard',
      icon: <DashboardOutlined />,
      label: '工作台',
    },
    {
      key: '/projects',
      icon: <ProjectOutlined />,
      label: '项目管理',
    },
    {
      key: '/tasks',
      icon: <ScheduleOutlined />,
      label: '任务中心',
    },
    {
      key: '/models',
      icon: <ApiOutlined />,
      label: '模型配置',
    },
    {
      key: '/access',
      icon: <SafetyCertificateOutlined />,
      label: '访问治理',
    },
  ];

  const selectedKey = location.pathname.startsWith('/projects')
    ? '/projects'
    : location.pathname;

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{
        display: 'flex',
        alignItems: 'center',
        background: '#fff',
        borderBottom: '1px solid #f0f0f0',
        padding: '0 24px'
      }}>
        <Title level={3} style={{ margin: 0, color: '#1890ff' }}>
          AI小说创作系统
        </Title>
      </Header>
      <Layout>
        <Sider width={200} style={{ background: '#fff' }}>
          <Menu
            mode="inline"
            selectedKeys={[selectedKey]}
            items={menuItems}
            onClick={({ key }) => navigate(key)}
            style={{ height: '100%', borderRight: 0 }}
          />
        </Sider>
        <Layout style={{ padding: '24px' }}>
          <Content
            style={{
              background: '#fff',
              padding: 24,
              margin: 0,
              minHeight: 280,
              borderRadius: 8,
            }}
          >
            <Outlet />
          </Content>
        </Layout>
      </Layout>
    </Layout>
  );
};

export default MainLayout;
