package com.locarya.domain.services

import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import org.typelevel.log4cats.Logger

class ComboServiceImpl[F[_]: Sync: Logger](
  comboRepo:      ComboRepository[F],
  itemRepo:       ItemRepository[F],
  bookingRepo:    BookingRepository[F],
  comboImageRepo: ComboImageRepository[F]
) extends ComboService[F]:

  def createCombo(request: CreateComboRequest): F[ComboId] =
    for
      items <- validateCompositionItems(request.itemCompositions, request.providerId)
      combo <- liftValidation(
                 Combo.create(
                   id              = ComboId.generate,
                   providerId      = request.providerId,
                   name            = request.name,
                   description     = request.description,
                   dailyRate       = request.dailyRate,
                   items           = request.itemCompositions,
                   requiresMonitor = inferRequiresMonitor(items)
                 )
               )
      stored       <- comboRepo.create(combo)
      images       <- liftValidation(ComboImage.create(stored.id, request.imageUrls))
      _            <- images.traverse_(comboImageRepo.create)
      _            <- Logger[F].info(
                        s"""{"event":"ComboCreated","comboId":"${stored.id.value}","providerId":"${stored.providerId.value}","itemIds":[${request.itemCompositions.map(c => s""""${c.itemId.value}"""").mkString(",")}]}"""
                      )
    yield stored.id

  def getCombo(comboId: ComboId, providerId: ProviderId): F[(Combo, List[ComboImage])] =
    for
      combo  <- requireComboExists(comboId)
      _      <- requireOwner(combo, providerId)
      images <- comboImageRepo.findByComboId(comboId)
    yield (combo, images)

  def updateCombo(request: UpdateComboRequest): F[Unit] =
    for
      combo                      <- requireComboExists(request.comboId)
      _                          <- requireOwner(combo, request.providerId)
      newResult <- request.itemCompositions match
                     case Some(compositions) =>
                       bookingRepo.existsForCombo(request.comboId).flatMap { exists =>
                         if exists then
                           Logger[F].warn(
                             s"""{"event":"ComboEditBlocked","comboId":"${request.comboId.value}","reason":"Has existing bookings"}"""
                           ) >> ComboError.HasBookings(request.comboId).raiseError[F, (List[ComboItemDefinition], Boolean)]
                         else
                           validateCompositionItems(compositions, request.providerId)
                             .map(items => (compositions, inferRequiresMonitor(items)))
                       }
                     case None => (combo.items, combo.requiresMonitor).pure[F]
      (newDefs, requiresMonitor) = newResult
      updated                    <- liftValidation(
                                      Combo.create(
                                        id              = combo.id,
                                        providerId      = combo.providerId,
                                        name            = request.name,
                                        description     = request.description,
                                        dailyRate       = request.dailyRate,
                                        items           = newDefs,
                                        requiresMonitor = requiresMonitor,
                                        isActive        = combo.isActive
                                      )
                                    )
      _                          <- comboRepo.update(updated)
      images                     <- liftValidation(ComboImage.create(combo.id, request.imageUrls))
      _                          <- comboImageRepo.replaceImages(combo.id, images)
    yield ()

  def listCombos(providerId: ProviderId): F[List[(Combo, List[ComboImage])]] =
    for
      combos <- comboRepo.findByProviderId(providerId)
      pairs  <- combos.traverse { combo =>
                  comboImageRepo.findByComboId(combo.id).map(imgs => (combo, imgs))
                }
    yield pairs

  def softDeleteCombo(comboId: ComboId, providerId: ProviderId): F[Unit] =
    for
      combo <- requireComboExists(comboId)
      _     <- requireOwner(combo, providerId)
      _     <- comboRepo.update(combo.deactivate)
      _     <- Logger[F].info(
                 s"""{"event":"ComboDeactivated","comboId":"${comboId.value}","providerId":"${providerId.value}"}"""
               )
    yield ()

  def activateCombo(comboId: ComboId, providerId: ProviderId): F[Unit] =
    for
      combo <- requireComboExists(comboId)
      _     <- requireOwner(combo, providerId)
      _     <- comboRepo.update(combo.activate)
      _     <- Logger[F].info(
                 s"""{"event":"ComboActivated","comboId":"${comboId.value}","providerId":"${providerId.value}"}"""
               )
    yield ()

  private def validateCompositionItems(
    compositions: List[ComboItemDefinition],
    providerId:   ProviderId
  ): F[List[Item]] =
    compositions.traverse { cd =>
      itemRepo.findById(cd.itemId).flatMap {
        case Some(item) if item.providerId != providerId =>
          ComboError.ItemBelongsToDifferentProvider(cd.itemId).raiseError[F, Item]
        case Some(item) =>
          item.pure[F]
        case None =>
          ComboId.fromString(cd.itemId.value) match
            case Right(comboId) =>
              comboRepo.findById(comboId).flatMap {
                case Some(_) => ComboError.ContainsNestedCombo(cd.itemId).raiseError[F, Item]
                case None    => ComboError.ItemNotFound(cd.itemId).raiseError[F, Item]
              }
            case Left(_) =>
              ComboError.ItemNotFound(cd.itemId).raiseError[F, Item]
      }
    }

  private def inferRequiresMonitor(items: List[Item]): Boolean =
    items.exists(_.requiresMonitor)

  private def liftValidation[A](e: Either[ValidationError, A]): F[A] =
    e.fold(err => ComboError.InvalidInput(err).raiseError[F, A], _.pure[F])

  private def requireComboExists(comboId: ComboId): F[Combo] =
    comboRepo.findById(comboId).flatMap {
      case Some(combo) => combo.pure[F]
      case None        => ComboError.NotFound(comboId).raiseError[F, Combo]
    }

  private def requireOwner(combo: Combo, providerId: ProviderId): F[Unit] =
    if combo.providerId == providerId then ().pure[F]
    else ComboError.Forbidden(combo.id).raiseError
