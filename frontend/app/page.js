export default function Home() {
  return (
    <div>
      <h1>Frontend (SPA - Next.js/React)</h1>
      <p>This is a placeholder for the URL shortener frontend.</p>
      <form onSubmit={async (e) => {
        e.preventDefault();
        const url = e.target.url.value;
        console.log(`Submitting URL: ${url}`);
        // TODO: Make API call to BFF
        alert(`URL submitted (simulated): ${url}`);
      }}>
        <input type="text" name="url" placeholder="Enter URL to shorten" style={{ width: '300px', marginRight: '10px' }} />
        <button type="submit">Shrink It!</button>
      </form>
    </div>
  );
}
