import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import MainLayout from './layouts/MainLayout'
import Dashboard from './pages/Dashboard'
import ProjectList from './pages/ProjectList'
import ProjectDetail from './pages/ProjectDetail'
import SampleManagement from './pages/SampleManagement'
import OutlineEditor from './pages/OutlineEditor'
import ChapterWriter from './pages/ChapterWriter'
import MemoryView from './pages/MemoryView'
import GraphView from './pages/GraphView'
import ModelProfiles from './pages/ModelProfiles'
import RetrievalView from './pages/RetrievalView'
import ArtifactView from './pages/ArtifactView'
import TaskCenter from './pages/TaskCenter'
import './App.css'

function App() {
  return (
    <ConfigProvider locale={zhCN}>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<MainLayout />}>
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="projects" element={<ProjectList />} />
            <Route path="tasks" element={<TaskCenter />} />
            <Route path="models" element={<ModelProfiles />} />
            <Route path="projects/:projectId" element={<ProjectDetail />} />
            <Route path="projects/:projectId/samples" element={<SampleManagement />} />
            <Route path="projects/:projectId/outline" element={<OutlineEditor />} />
            <Route path="projects/:projectId/chapters" element={<ChapterWriter />} />
            <Route path="projects/:projectId/memory" element={<MemoryView />} />
            <Route path="projects/:projectId/graph" element={<GraphView />} />
            <Route path="projects/:projectId/retrieval" element={<RetrievalView />} />
            <Route path="projects/:projectId/artifacts" element={<ArtifactView />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </ConfigProvider>
  )
}

export default App
