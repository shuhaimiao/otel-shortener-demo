"use client";

import { useState } from 'react';

export default function Home() {
  const [url, setUrl] = useState('');
  const [shortUrl, setShortUrl] = useState('');
  const [error, setError] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();

    try {
      setError('');
      setShortUrl('');

      const response = await fetch('http://localhost:3001/api/links', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ url }),
      });

      if (!response.ok) {
        const errorData = await response.json();
        const errorMessage = errorData.message || `Error: ${response.status}`;
        throw new Error(errorMessage);
      }

      const data = await response.json();
      setShortUrl(data.shortUrl);
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <div>
      <h1>URL Shortener</h1>
      <p>Enter a long URL to make it short.</p>
      <form onSubmit={handleSubmit}>
        <input
          type="text"
          name="url"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          placeholder="Enter URL to shorten"
          style={{ width: '400px', marginRight: '10px', padding: '8px' }}
        />
        <button type="submit" style={{ padding: '8px 12px' }}>Shrink It!</button>
      </form>

      {shortUrl && (
        <div style={{ marginTop: '20px' }}>
          <h2>Short URL:</h2>
          <a href={shortUrl} target="_blank" rel="noopener noreferrer">
            {shortUrl}
          </a>
        </div>
      )}

      {error && (
        <div style={{ marginTop: '20px', color: 'red' }}>
          <h2>Error:</h2>
          <p>{error}</p>
        </div>
      )}
    </div>
  );
}
