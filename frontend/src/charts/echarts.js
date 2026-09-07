import * as echarts from 'echarts/core'
import { BarChart, CandlestickChart, LineChart } from 'echarts/charts'
import {
  DataZoomInsideComponent,
  DataZoomSliderComponent,
  GridComponent,
  LegendPlainComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  BarChart,
  CandlestickChart,
  LineChart,
  DataZoomInsideComponent,
  DataZoomSliderComponent,
  GridComponent,
  LegendPlainComponent,
  TooltipComponent,
  CanvasRenderer,
])

export const init = echarts.init
