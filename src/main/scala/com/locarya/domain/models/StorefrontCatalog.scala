package com.locarya.domain.models

case class ItemWithImages(item: Item, images: List[ItemImage])

case class ComboCompositionItem(item: Item, quantity: Int, images: List[ItemImage])

case class ComboWithComposition(combo: Combo, compositions: List[ComboCompositionItem])

case class StorefrontCatalog(
  provider: Provider,
  items:    List[ItemWithImages],
  combos:   List[ComboWithComposition]
)
