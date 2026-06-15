# Locarya - Plataforma SaaS para Locação de Brinquedos de Festa

Uma plataforma que permite pequenos negócios de locação de brinquedos de festa infantil digitalizarem suas operações, gerenciarem reservas, contratos, pagamentos e disponibilidade de equipamentos.

## Language

### Atores

**Locador**:
Dono do negócio que aluga os brinquedos. É quem usa o sistema para gerenciar seu catálogo, reservas e pagamentos. Pode ser pessoa física (CPF) ou jurídica (CNPJ) — exatamente um dos dois é obrigatório. Tem localização (cidade + estado) que aparece na Loja.
_Avoid_: Proprietário, fornecedor, vendedor, usuário

**Cliente**:
Pessoa que faz a reserva dos brinquedos (geralmente mãe/pai organizando festa infantil).
_Avoid_: Locatário, consumidor, usuário, comprador

**Monitor**:
Pessoa cadastrada pelo Locador para supervisionar/recrear durante o uso de certos equipamentos. Tem nome, telefone e disponibilidade.
_Avoid_: Recreador, atendente, funcionário

### Produtos e Inventário

**Item**:
Qualquer coisa que pode ser alugada — individual (ex: uma cama elástica) ou agrupada (Combo). Cada Item tem quantidade em estoque, configuração de Monitor (obrigatório/opcional/não permite), status (ativo/inativo) e **imagens** (1 principal obrigatória + até 4 adicionais, máximo 5). Primeira imagem é o destaque na Loja, demais formam galeria. Apenas Itens ativos aparecem na Loja. Itens que já têm Reservas não podem ser deletados, apenas desativados.
_Avoid_: Produto, brinquedo (como termo técnico), equipamento

**Combo**:
Um tipo de Item que agrupa outros Itens individuais (ex: "Combo Festa Completa" = 1 cama elástica + 1 piscina de bolinha). O Locador cria Combos através de uma feature "Montar Combo" que permite selecionar Itens individuais e definir preço manual. Quando uma Reserva inclui um Combo, os Itens individuais que o compõem são descontados do estoque. Se qualquer Item individual estiver indisponível, o Combo também fica indisponível. **MVP:** Combos só podem conter Itens individuais (não outros Combos). Combos que já têm Reservas não podem ser editados — criar novo Combo para mudanças. Combos têm **imagens próprias** (1 principal obrigatória + até 4 adicionais, máximo 5), independentes das imagens dos Itens que os compõem. Primeira imagem é o destaque na Loja, demais formam galeria. Na Loja, o Combo exibe suas próprias imagens e também as imagens dos Itens individuais que o compõem.
_Avoid_: Pacote, kit, bundle

### Operações

**Reserva**:
Compromisso completo que inclui quais Itens (e quantidades), data, horário (opcional), endereço de entrega (rua, número, bairro, cidade, estado, CEP, complemento), Cliente, Pagamentos, status de entrega e status geral. O endereço de entrega é armazenado diretamente na Reserva (não no cadastro do Cliente). Uma Reserva pode conter múltiplos Itens para a mesma festa.
_Avoid_: Pedido, locação, agendamento

**Pagamento**:
Registro de um pagamento recebido para uma Reserva. Uma Reserva pode ter múltiplos Pagamentos (parciais ou integral). Processado via split payment (Asaas) — vai direto para o Locador, taxa de serviço é deduzida automaticamente.
_Avoid_: Entrada, sinal, parcela (use valores concretos)

**Contrato**:
Documento PDF gerado a partir de template customizável, associado a uma Reserva quando o Locador habilita uso de contratos. Tem status (pendente assinatura, assinado).
_Avoid_: Termo, acordo

### Plataforma

**Loja**:
Vitrine pública do Locador acessível por URL única (ex: `locarya.com.br/loja/brinquedos-inflaveis-me-sea6`). Onde Clientes navegam Itens e criam Reservas.
_Avoid_: Site, catálogo, vitrine

**Dashboard**:
Área administrativa (`dashboard.locarya.com.br`) onde o Locador gerencia Itens, Reservas, Monitores, Pagamentos, Contratos.
_Avoid_: Painel, admin, backoffice

**Plano**:
Nível de assinatura do Locador (Freemium ou Premium). Determina formato da URL da Loja, limites (quantidade de Itens, Reservas/mês) e funcionalidades (Contratos, integração de pagamento, tipo de suporte).
_Avoid_: Assinatura, tier, nível

## Relações e Regras

- Um **Locador** tem uma **Loja** (vitrine pública) e acessa o **Dashboard** (área administrativa)
- Um **Locador** tem um **Plano** (Freemium ou Premium)
- Um **Locador** pode ser pessoa física (tem CPF) ou jurídica (tem CNPJ) — exatamente um dos dois é obrigatório, nunca ambos
- Um **Locador** tem cidade + estado (localização de serviço, exibida na Loja)
- Um **Locador** cadastra **Itens** (com quantidade em estoque) e **Monitores** (pessoas disponíveis)
- Um **Item** pode ser simples ou um **Combo** (que agrupa outros Itens individuais)
- Um **Combo** é criado pelo Locador selecionando Itens individuais e definindo preço manual (MVP: não há cálculo automático de desconto)
- Um **Combo** pode conter apenas Itens individuais (MVP: não pode conter outros Combos)
- Um **Combo** que já tem Reservas não pode ser editado — para mudar composição, criar novo Combo e desativar o antigo
- Quando um **Combo** é adicionado a uma **Reserva**, os Itens individuais que o compõem são consumidos do estoque
- Se qualquer Item individual que compõe um **Combo** estiver indisponível em uma data, o **Combo** também fica indisponível naquela data
- **Itens** e **Combos** têm status ativo/inativo — apenas ativos aparecem na Loja
- **Itens** ou **Combos** que já têm Reservas não podem ser deletados, apenas desativados
- **Itens** têm 1 imagem principal (obrigatória) + até 4 imagens adicionais (total: 5 máximo). Armazenadas em tabela separada com ordem de exibição
- **Combos** têm 1 imagem principal (obrigatória) + até 4 imagens adicionais (total: 5 máximo), independentes das imagens dos Itens que os compõem. Armazenadas em tabela separada com ordem de exibição. Obrigatórias na criação e substituição total no update
- Um **Item** pode exigir **Monitor** (obrigatório), permitir **Monitor** (opcional) ou não permitir
- Uma **Reserva** pertence a um **Locador** e a um **Cliente**
- Uma **Reserva** contém um ou mais **Itens** (individuais e/ou Combos), uma data, endereço de entrega
- Uma **Reserva** pode ter **Pagamentos** associados
- Uma **Reserva** pode ter um **Contrato** (se o Locador habilitar contratos)
- Uma **Reserva** que requer Monitor deve ter um **Monitor** atribuído
- Disponibilidade de **Item**: se há Reserva Confirmada para uma data, a quantidade do Item (ou dos Itens que compõem o Combo) é descontada do estoque para aquele dia inteiro (MVP não considera horários para disponibilidade)

## Status de Reserva

- **Pendente** — criada pelo Cliente via Loja, aguardando confirmação do Locador
- **Confirmada** — Locador aceitou (ou Locador criou via Dashboard)
- **Em andamento** — Equipamento entregue, festa acontecendo
- **Concluída** — Equipamento devolvido, Reserva finalizada
- **Cancelada** — Cancelada antes da entrega

## Fluxos Principais

### Criação de Reserva

**Via Loja (Cliente cria):**
Cliente navega Loja → adiciona Itens → preenche data e endereço → cria Reserva (status: Pendente) → Locador revisa no Dashboard → confirma (status: Confirmada)

**Via Dashboard (Locador cria):**
Locador recebe lead (ex: WhatsApp) → cria Reserva manualmente no Dashboard com dados do Cliente → Reserva já começa Confirmada

### Pagamento

**Checkout na Loja:**
Cliente finaliza Reserva → paga direto no checkout (integração Asaas) → Pagamento registrado

**Link de pagamento:**
Locador gera link de pagamento no Dashboard → envia para Cliente (ex: WhatsApp) → Cliente paga → Pagamento registrado

**Split payment:** Dinheiro vai direto para conta do Locador, taxa de serviço é deduzida automaticamente.

### Cancelamento e Reembolso

**Antes do Pagamento:** Apenas cancela, sem complicação.

**Depois do Pagamento:** Locador decide se reembolsa (e quanto). Taxa de serviço só é reembolsada se houver reembolso total nas primeiras 24h.

## Exemplo de Diálogo

**Dev:** Então quando a mãe acessa a Loja do João Brinquedos e adiciona uma cama elástica para sábado, isso cria uma Reserva?

**Domain Expert (Cleiton):** Sim, mas a Reserva começa como Pendente. O João precisa confirmar no Dashboard dele, porque pode ser que ele já tenha prometido aquela cama elástica para outro Cliente por WhatsApp.

**Dev:** E se o João só tem uma cama elástica cadastrada, e já tem uma Reserva Confirmada para sábado, o sistema bloqueia?

**Domain Expert:** Exato. Cada Item tem quantidade em estoque. Se a quantidade disponível para aquele dia é zero, o sistema avisa que não dá.

**Dev:** E se for um Combo — tipo "Festa Completa" que tem cama elástica + piscina de bolinha — como funciona a disponibilidade?

**Domain Expert:** O João cria o Combo usando a feature "Montar Combo", onde ele seleciona os Itens individuais (1 cama elástica + 1 piscina de bolinha) e define o preço do pacote. Quando alguém reserva esse Combo para sábado, o sistema desconta **1 cama elástica e 1 piscina de bolinha do estoque para sábado**. Se qualquer um desses Itens individuais já estiver sem estoque para sábado, o Combo também fica indisponível.

**Dev:** Então se o João tem 2 camas elásticas e alguém reserva 1 individual para sábado, sobra 1 disponível?

**Domain Expert:** Exatamente. E se outra pessoa tentar reservar o Combo "Festa Completa" para sábado, vai funcionar porque ainda tem 1 cama elástica disponível. Mas se alguém reservar mais 1 cama individual, aí o Combo fica bloqueado para aquele dia.

**Dev:** E o preço do Combo — pode ser diferente da soma dos Itens individuais?

**Domain Expert:** Sim! O João define o preço do Combo na hora que monta. Geralmente é mais barato que comprar separado (incentivo para o Cliente fechar o pacote).

**Dev:** E o Monitor? Se a cama elástica gigante precisa obrigatoriamente de Monitor, e o João só tem um Monitor disponível, isso também bloqueia?

**Domain Expert:** Sim, mas isso é validado quando o João confirma a Reserva. O sistema avisa "você precisa atribuir um Monitor para esse Item". Se não tem Monitor disponível naquela data, ele não consegue confirmar.

**Dev:** E pagamento — quando o Cliente paga no checkout, o dinheiro vai direto pro João?

**Domain Expert:** Vai direto (split payment no Asaas). A nossa taxa de serviço é deduzida automaticamente. O João vê quanto ele vai receber líquido.

**Dev:** E se cancelar depois de pago?

**Domain Expert:** O João decide se reembolsa. A nossa taxa só volta se ele fizer reembolso total nas primeiras 24h — senão a gente fica com a taxa (anti-abuso).
