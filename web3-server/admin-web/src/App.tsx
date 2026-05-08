import { useCallback, useEffect, useRef, useState } from 'react';
import {
  DashboardOutlined,
  GiftOutlined,
  HistoryOutlined,
  LogoutOutlined,
  MobileOutlined,
  ReloadOutlined,
  RollbackOutlined,
  SettingOutlined,
  WalletOutlined,
} from '@ant-design/icons';
import { PageContainer, ProLayout } from '@ant-design/pro-components';
import { Button, Spin, message } from 'antd';
import { Link, Navigate, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { adminRequest } from './api';
import LoginPage from './pages/Login';
import DashboardPage from './pages/Dashboard';
import PacketsPage from './pages/Packets';
import PacketDetailPage from './pages/PacketDetail';
import ClaimsPage from './pages/Claims';
import RefundsPage from './pages/Refunds';
import WalletsPage from './pages/Wallets';
import ClientVersionsPage from './pages/ClientVersions';
import SystemPage from './pages/System';

type AdminUser = {
  username: string;
};

function LoadingPage() {
  return (
    <div className="admin-spin-page">
      <Spin size="large" />
    </div>
  );
}

function AdminShell({ user, onLogout }: { user: AdminUser; onLogout: () => void }) {
  const navigate = useNavigate();
  const location = useLocation();

  const logout = async () => {
    try {
      await adminRequest('/auth/logout', { method: 'POST', suppressAuthRequired: true });
    } catch {
      // ignore logout network errors and clear local auth state anyway
    }
    onLogout();
    navigate('/login', { replace: true });
  };

  const route = {
    path: '/',
    routes: [
      { path: '/dashboard', name: '控制台', icon: <DashboardOutlined /> },
      { path: '/packets', name: '红包列表', icon: <GiftOutlined /> },
      { path: '/claims', name: '领取记录', icon: <HistoryOutlined /> },
      { path: '/refunds', name: '退款记录', icon: <RollbackOutlined /> },
      { path: '/wallets', name: '钱包统计', icon: <WalletOutlined /> },
      { path: '/client-versions', name: '客户端版本', icon: <MobileOutlined /> },
      { path: '/system', name: '系统状态', icon: <SettingOutlined /> },
    ],
  };

  return (
    <ProLayout
      title="红包管理平台"
      logo={<GiftOutlined />}
      route={route}
      location={{ pathname: location.pathname }}
      layout="mix"
      fixedHeader
      fixSiderbar
      token={{ header: { colorBgHeader: '#fff' } }}
      menuItemRender={(item, dom) => {
        if (!item.path) return dom;
        return <Link to={item.path}>{dom}</Link>;
      }}
      actionsRender={() => [
        <Button key="refresh" type="text" icon={<ReloadOutlined />} onClick={() => window.location.reload()} />,
        <Button key="logout" type="text" icon={<LogoutOutlined />} onClick={logout}>
          退出
        </Button>,
      ]}
      avatarProps={{ title: user.username }}
    >
      <PageContainer ghost>
        <Outlet />
      </PageContainer>
    </ProLayout>
  );
}

export default function App() {
  const location = useLocation();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [user, setUser] = useState<AdminUser | null>(null);
  const lastAuthWarningAtRef = useRef(0);

  const loadCurrentUser = useCallback(async () => {
    setLoading(true);
    try {
      const data = await adminRequest<AdminUser>('/auth/me', { suppressAuthRequired: true });
      setUser(data);
    } catch {
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadCurrentUser();
  }, [loadCurrentUser]);

  useEffect(() => {
    const handler = () => {
      setUser(null);
      navigate('/login', { replace: true });

      // 多个接口同时返回 401 或 React 开发模式 StrictMode 二次执行时，只提示一次。
      const now = Date.now();
      if (location.pathname !== '/login' && now - lastAuthWarningAtRef.current > 2500) {
        lastAuthWarningAtRef.current = now;
        message.warning('登录已失效，请重新登录');
      }
    };

    window.addEventListener('admin-auth-required', handler);
    return () => window.removeEventListener('admin-auth-required', handler);
  }, [location.pathname, navigate]);

  if (loading) return <LoadingPage />;

  return (
    <Routes>
      <Route
        path="/login"
        element={user ? <Navigate to="/dashboard" replace /> : <LoginPage onLogin={(nextUser) => setUser(nextUser)} />}
      />
      <Route
        path="/"
        element={user ? <AdminShell user={user} onLogout={() => setUser(null)} /> : <Navigate to="/login" replace />}
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="packets" element={<PacketsPage />} />
        <Route path="packets/:packetId" element={<PacketDetailPage />} />
        <Route path="claims" element={<ClaimsPage />} />
        <Route path="refunds" element={<RefundsPage />} />
        <Route path="wallets" element={<WalletsPage />} />
        <Route path="client-versions" element={<ClientVersionsPage />} />
        <Route path="system" element={<SystemPage />} />
      </Route>
      <Route path="*" element={<Navigate to={user ? '/dashboard' : '/login'} replace />} />
    </Routes>
  );
}
