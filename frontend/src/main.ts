import { createApp } from "vue";
import { createRouter, createWebHashHistory } from "vue-router";
import App from "./App.vue";
import "./style.css";
const router = createRouter({
  history: createWebHashHistory("/console/"),
  routes: [
    {
      path: "/",
      component: () => import("./views/OverviewView.vue"),
      meta: { title: "网关概览" },
    },
    {
      path: "/models",
      component: () => import("./views/ModelsView.vue"),
      meta: { title: "模型管理" },
    },
    {
      path: "/keys",
      component: () => import("./views/KeysView.vue"),
      meta: { title: "上游密钥" },
    },
    {
      path: "/users",
      component: () => import("./views/UsersView.vue"),
      meta: { title: "用户与令牌" },
    },
    {
      path: "/mcp",
      component: () => import("./views/McpView.vue"),
      meta: { title: "MCP 管理" },
    },
    {
      path: "/logs",
      component: () => import("./views/LogsView.vue"),
      meta: { title: "日志与用量" },
    },
    {
      path: "/playground",
      component: () => import("./views/PlaygroundView.vue"),
      meta: { title: "在线调试" },
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
createApp(App).use(router).mount("#app");
