// DSH Web 前端插件：在左侧边栏注册入口，主面板用同源 iframe 打开管理页。
window.__ModuleLoader__.load({
  id: 'dsh-channel',
  factory: (require) => {
    const module = { exports: {} }
    const React = require('react')
    const PANEL_ID = 'dsh-channel:panel'

    const LABEL = 'dsh-channel-charactor'
    const MainPanel = () => React.createElement('iframe', {
      src: '/dsh-channel/admin',
      title: LABEL,
      style: { display: 'block', width: '100%', height: '100%', minHeight: '100vh', border: 0, background: 'transparent' },
    })
    const SidebarIcon = ({ size = 18 }) => React.createElement('span', {
      'data-dsh-plugin': 'dsh-channel',
      'data-dsh-part': 'sidebar-icon',
      style: { display: 'inline-flex' },
    }, React.createElement('svg', {
      width: size, height: size, viewBox: '0 0 24 24', fill: 'none',
      stroke: 'currentColor', strokeWidth: 1.7, strokeLinecap: 'round', strokeLinejoin: 'round',
      'aria-hidden': 'true',
    },
      React.createElement('path', { d: 'M3 8h14 M13 4l4 4-4 4' }),
      React.createElement('path', { d: 'M21 16H7 M11 12l-4 4 4 4' })
    ))

    module.exports.inject = ['slots']
    module.exports.apply = (ctx) => {
      ctx.slots.inject('main', () => ctx.slots.inject('sidebar.panellist', () => {
        const stopMain = ctx.slots.register({ name: 'main', key: PANEL_ID, inject: () => ({}) }, MainPanel)
        let stopIcon
        try {
          stopIcon = ctx.slots.register({
            name: 'sidebar.panellist', id: PANEL_ID, order: 35, label: () => LABEL,
          }, SidebarIcon)
        } catch (error) {
          stopMain()
          throw error
        }
        return () => { stopIcon?.(); stopMain() }
      }))
    }
    return module.exports
  },
})
