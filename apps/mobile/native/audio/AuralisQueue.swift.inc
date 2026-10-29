  // AURALIS_QUEUE_EDITS: retain the current AVPlayerItem and its decoder position.
  private func auralisPreserveCurrent(_ updated: [AudioSource], index: Int, item: AVPlayerItem) {
    for queued in ref.items() where queued !== item { ref.remove(queued) }
    sources = updated
    previousTrackIndex = index
    playerItems = [AVPlayerItem?](repeating: nil, count: updated.count)
    playerItems[index] = item
    if index + 1 < updated.count {
      for i in (index + 1)..<updated.count {
        let next = AudioUtils.createAVPlayerItem(from: updated[i])
        playerItems[i] = next
        if let next { ref.insert(next, after: nil) }
      }
    }
    updateStatus(with: ["currentIndex": index, "trackCount": trackCount])
  }
  func moveAuralisQueue(_ from: Int, _ to: Int) {
    guard validIndex(from), validIndex(to), from != to else { return }
    let selected = currentTrackIndex
    var updated = sources
    let source = updated.remove(at: from)
    updated.insert(source, at: to)
    let index = selected == from ? to : from < selected && to >= selected ? selected - 1 : from > selected && to <= selected ? selected + 1 : selected
    if let current = ref.currentItem { auralisPreserveCurrent(updated, index: index, item: current) }
    else { sources = updated; previousTrackIndex = index; rebuildPlaylist(startingAt: index) }
  }
  func insert(source: AudioSource, at index: Int) {
    guard index >= 0 && index <= sources.count else { return }
    let selected = currentTrackIndex
    var updated = sources; updated.insert(source, at: index)
    if let current = ref.currentItem { auralisPreserveCurrent(updated, index: selected + (index <= selected ? 1 : 0), item: current) }
    else { sources = updated; previousTrackIndex = 0; rebuildPlaylist(startingAt: 0); updateStatus(with: ["trackCount": trackCount]) }
  }
  func remove(at index: Int) {
    guard validIndex(index) else { return }
    let selected = currentTrackIndex, playing = isPlaying
    var updated = sources; updated.remove(at: index)
    if index != selected, let current = ref.currentItem { auralisPreserveCurrent(updated, index: selected - (index < selected ? 1 : 0), item: current) }
    else {
      sources = updated; previousTrackIndex = min(selected, max(0, updated.count - 1))
      if updated.isEmpty { ref.pause(); ref.removeAllItems(); playerItems = [] }
      else { rebuildPlaylist(startingAt: previousTrackIndex); if playing { play(at: currentRate) } }
      updateStatus(with: ["currentIndex": previousTrackIndex, "trackCount": trackCount])
    }
  }
