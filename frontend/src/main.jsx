import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import TalentOpsApp from './TalentOpsApp.jsx'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <TalentOpsApp />
  </StrictMode>,
)
