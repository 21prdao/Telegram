import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { Alert, Card, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { adminRequest } from '../api';

type AdminUser = {
  username: string;
};

type PublicConfig = {
  enabled: boolean;
  username: string;
};

export default function LoginPage({ onLogin }: { onLogin: (user: AdminUser) => void }) {
  const [config, setConfig] = useState<PublicConfig>({ enabled: true, username: 'admin' });

  useEffect(() => {
    adminRequest<PublicConfig>('/auth/config', { suppressAuthRequired: true })
      .then(setConfig)
      .catch(() => undefined);
  }, []);

  return (
    <div className="login-page">
      <Card className="login-card" bordered={false}>
        <LoginForm
          className="login-form"
          title="ETZone 红包管理平台"
          subTitle="独立 Ant Design Pro 后台前端"
          initialValues={{ username: config.username }}
          submitter={{ searchConfig: { submitText: '登录' } }}
          onFinish={async (values) => {
            try {
              const data = await adminRequest<AdminUser>('/auth/login', {
                method: 'POST',
                body: JSON.stringify(values),
                suppressAuthRequired: true,
              });
              message.success('登录成功');
              onLogin(data);
              return true;
            } catch (error) {
              message.error(error instanceof Error ? error.message : '登录失败');
              return false;
            }
          }}
        >
          {!config.enabled && (
            <Alert
              type="warning"
              showIcon
              message="后台登录未启用"
              description="请在服务端环境变量中配置 ADMIN_PASSWORD 或 ADMIN_TOKEN。"
              style={{ marginBottom: 16 }}
            />
          )}
          <ProFormText
            name="username"
            fieldProps={{ prefix: <UserOutlined /> }}
            placeholder="用户名"
            rules={[{ required: true, message: '请输入用户名' }]}
          />
          <ProFormText.Password
            name="password"
            fieldProps={{ prefix: <LockOutlined /> }}
            placeholder="密码 / ADMIN_TOKEN"
            rules={[{ required: true, message: '请输入密码' }]}
          />
          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            生产环境建议通过 HTTPS 访问，并配置 ADMIN_SESSION_SECRET。
          </Typography.Paragraph>
        </LoginForm>
      </Card>
    </div>
  );
}
