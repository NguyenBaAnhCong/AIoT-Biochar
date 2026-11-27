export const tv4Api = (api) => ({
  quality: (path = '') => api(`/tv4/quality${path}`),
  datasets: (path = '') => api(`/tv4/datasets${path}`),
});
