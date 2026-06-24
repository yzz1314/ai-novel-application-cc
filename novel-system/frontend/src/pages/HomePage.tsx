import { Typography, Card } from 'antd'

const { Title, Paragraph } = Typography

function HomePage() {
  return (
    <div style={{ padding: '50px', maxWidth: '1200px', margin: '0 auto' }}>
      <Title level={1}>小说样本拆解与长篇创作系统</Title>
      
      <Card style={{ marginTop: '20px' }}>
        <Title level={3}>🚀 系统已启动</Title>
        <Paragraph>
          欢迎使用小说创作系统！系统正在运行中...
        </Paragraph>
        
        <Paragraph>
          <strong>核心功能：</strong>
          <ul>
            <li>样本上传与拆解</li>
            <li>作者风格分析</li>
            <li>大纲生成</li>
            <li>章节创作</li>
            <li>记忆管理</li>
          </ul>
        </Paragraph>
        
        <Paragraph type="secondary">
          完整功能正在开发中，敬请期待...
        </Paragraph>
      </Card>
    </div>
  )
}

export default HomePage
