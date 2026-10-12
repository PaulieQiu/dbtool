<template>
  <el-container style="height: 100vh">
    <el-header style="background: #001529; color: #fff; display: flex; align-items: center; padding: 0 20px;">
      <span style="font-size: 18px; font-weight: 600;">数据库工作台</span>
      <span style="margin-left: 16px; color: #999; font-size: 12px;">DBTool OpenSource</span>
    </el-header>
    <el-container>
      <el-aside width="180px" style="background: #fff; border-right: 1px solid #eee;">
        <el-menu :default-active="active" @select="onSelect">
          <el-menu-item index="driver">
            <el-icon><Download /></el-icon><span>驱动管理</span>
          </el-menu-item>
          <el-menu-item index="conn">
            <el-icon><Connection /></el-icon><span>数据源管理</span>
          </el-menu-item>
          <el-menu-item index="tool">
            <el-icon><Monitor /></el-icon><span>数据库工作台</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main style="background: #f5f7fa; padding: 0;">
        <DriverView v-if="active === 'driver'" />
        <ConnView v-else-if="active === 'conn'" />
        <ToolView v-else-if="active === 'tool'" />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import ConnView from '@/views/dbtool/conn/index.vue'
import ToolView from '@/views/dbtool/tool/index.vue'
import DriverView from '@/views/dbtool/driver/index.vue'

const active = ref('tool')
const onSelect = (idx: string) => { active.value = idx }
</script>
