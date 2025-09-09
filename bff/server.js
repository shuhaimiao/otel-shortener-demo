const express = require('express');
const cors = require('cors');
const { establishContext, checkAuthorization } = require('./middleware/context');
const app = express();
const port = process.env.PORT || 3001;

// Enable CORS for all routes
app.use(cors());

app.use(express.json());

// Apply context establishment middleware to all routes
app.use(establishContext);

// Placeholder for Keycloak user token validation
// Now using context from establishContext middleware
const validateUserToken = (req, res, next) => {
  console.log('BFF: Validating user token (placeholder)');
  // Context is already established by establishContext middleware
  // For demo purposes, accept all requests (even anonymous)
  // In production, this would properly validate JWT tokens
  if (req.userContext) {
    console.log(`BFF: User ${req.userContext.user_id} validated for tenant ${req.userContext.tenant_id}`);
    next();
  } else {
    // If no context at all, something is wrong
    res.status(500).json({ error: 'Context not established' });
  }
};

// Placeholder for M2M token acquisition
const getM2MToken = async () => {
  console.log('BFF: Acquiring M2M token (placeholder)');
  // In a real app, this would call Keycloak's token endpoint
  return 'mock-m2m-token'; // Simulate a token
};

app.post('/api/links', validateUserToken, checkAuthorization('create:links'), async (req, res) => {
  console.log('BFF: Received POST /api/links');
  const { url } = req.body;

  if (!url) {
    return res.status(400).json({ error: 'URL is required' });
  }

  // Context already established and authorization checked by middleware
  const userContext = req.userContext;
  console.log(`BFF: Processing request for user ${userContext.user_id} in tenant ${userContext.tenant_id}`);

  try {
    const m2mToken = await getM2MToken();
    const urlApiHostname = process.env.URL_API_HOSTNAME || 'url-api';
    const urlApiPort = process.env.URL_API_PORT || 8080;

    console.log(`BFF: Calling URL API at http://${urlApiHostname}:${urlApiPort}/links with M2M token.`);

    // Use the real fetch API to call the URL API with propagated context headers
    const response = await fetch(`http://${urlApiHostname}:${urlApiPort}/links`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${m2mToken}`,
        // Propagate all context headers
        'X-User-ID': req.headers['x-user-id'],
        'X-Tenant-ID': req.headers['x-tenant-id'],
        'X-User-Email': req.headers['x-user-email'],
        'X-User-Groups': req.headers['x-user-groups'],
        'X-Service-Name': req.headers['x-service-name'],
        'X-Transaction-Name': req.headers['x-transaction-name'],
        'X-Correlation-ID': req.headers['x-correlation-id'],
        // Propagate trace context
        'traceparent': req.headers['traceparent'],
        'tracestate': req.headers['tracestate']
      },
      body: JSON.stringify({ 
        url, 
        userId: userContext.user_id, 
        tenantId: userContext.tenant_id 
      })
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
