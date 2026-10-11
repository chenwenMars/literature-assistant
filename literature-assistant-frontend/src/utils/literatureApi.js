import axios from 'axios'

export async function literatureRequest(method, path, data) {
  const response = await axios({ method, url: `/api/literature/${path}`, data })
  if (!response.data.success) throw new Error(response.data.message)
  return response.data.data
}
