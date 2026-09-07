export function useLatestRequest() {
  const versions = new Map()

  function begin(key = 'default') {
    const version = (versions.get(key) || 0) + 1
    versions.set(key, version)
    return version
  }

  function isLatest(version, key = 'default') {
    return versions.get(key) === version
  }

  function invalidate(key = 'default') {
    versions.set(key, (versions.get(key) || 0) + 1)
  }

  return { begin, isLatest, invalidate }
}
