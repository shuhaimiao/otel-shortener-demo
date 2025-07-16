const express = require('express');
const cors = require('cors');
const app = express();
const port = process.env.PORT || 3001;

// Enable CORS for all routes
app.use(cors());

app.use(express.json());

// Placeholder for Keycloak user token validation
const validateUserToken = (req, res, next) => {
  console.log('BFF: Validating user token (placeholder)');
  // In a real app, this would involve checking a JWT from Keycloak
  // For now, we'll assume the token is valid
  req.user = { id: 'test-user-id', tenantId: 'test-tenant-id', scopes: ['create:links'] }; // Mock user
  next();
};

// Placeholder for M2M token acquisition
const getM2MToken = async () => {
  console.log('BFF: Acquiring M2M token (placeholder)');
  // In a real app, this would call Keycloak's token endpoint
  return 'mock-m2m-token'; // Simulate a token
};

app.post('/api/links', validateUserToken, async (req, res) => {
  console.log('BFF: Received POST /api/links');
  const { url } = req.body;

  if (!url) {
    return res.status(400).json({ error: 'URL is required' });
  }

  // Authorization check (as per README)
  if (!req.user || !req.user.scopes || !req.user.scopes.includes('create:links')) {
    console.log('BFF: User not authorized. Missing required scope.');
    return res.status(403).json({ error: 'Forbidden' });
  }
  console.log(`BFF: User ${req.user.id} is authorized for tenant ${req.user.tenantId}.`);

  try {
    const m2mToken = await getM2MToken();
    const urlApiHostname = process.env.URL_API_HOSTNAME || 'url-api';
    const urlApiPort = process.env.URL_API_PORT || 8080;

    console.log(`BFF: Calling URL API at http://${urlApiHostname}:${urlApiPort}/links with M2M token.`);

    // Use the real fetch API to call the URL API
    const response = await fetch(`http://${urlApiHostname}:${urlApiPort}/links`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${m2mToken}`,
        'X-User-ID': req.user.id,
        'X-Tenant-ID': req.user.tenantId,
      },
      body: JSON.stringify({ url, userId: req.user.id, tenantId: req.user.tenantId })
    });

    if (!response.ok) {
      const errorData = await response.text();
      console.error('BFF: Error from URL API:', errorData);
      return res.status(response.status).json({ error: `Error from URL API: ${errorData}` });
    }
    const data = await response.json();

    // Simulate successful response from URL API
    // const simulatedApiResponse = { shortCode: Math.random().toString(36).substring(2, 8) };
    // console.log('BFF: Simulated response from URL API:', simulatedApiResponse);

    const bffHostname = process.env.BFF_HOSTNAME || 'localhost';
    const redirectServicePort = process.env.REDIRECT_SERVICE_EXTERNAL_PORT || 8081; // Port for external access to redirector

    res.status(201).json({
      shortUrl: `http://${bffHostname}:${redirectServicePort}/${data.shortCode}`
    });

  } catch (error) {
    console.error('BFF: Error processing /api/links:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

app.get('/health', (req, res) => {
  res.status(200).send('OK');
});

app.listen(port, () => {
  console.log(`BFF listening at http://localhost:${port}`);
});
