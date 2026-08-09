local function trim(value)
  return value:match("^%s*(.-)%s*$")
end

local function is_safe_http(target)
  local normalized = trim(target):lower()
  return normalized:match("%s") == nil
      and normalized:match("^https?://[^/%s%?#]+") ~= nil
end

local function is_safe_external_link(target)
  local normalized = trim(target):lower()
  return is_safe_http(normalized) or normalized:match("^mailto:[^%s]+$") ~= nil
end

local function is_managed_image(target)
  local normalized = trim(target)
  local managed_name = normalized:match("^image%-%d+%-%d+%.png$") ~= nil
      or normalized:match("^image%-%d+%-%d+%.jpg$") ~= nil
      or normalized:match("^smiles%-%d+%-%d+%.png$") ~= nil
      or normalized:match("^mermaid%-%d+%-%d+%.png$") ~= nil
  if not managed_name then
    return false
  end

  local file = io.open(normalized, "rb")
  if file == nil then
    return false
  end
  file:close()
  return true
end

local function is_default_emoji(codepoint)
  return (codepoint >= 0x1F000 and codepoint <= 0x1FAFF)
      or codepoint == 0x231A
      or codepoint == 0x231B
      or (codepoint >= 0x23E9 and codepoint <= 0x23EC)
      or codepoint == 0x23F0
      or codepoint == 0x23F3
      or (codepoint >= 0x25FD and codepoint <= 0x25FE)
      or (codepoint >= 0x2614 and codepoint <= 0x2615)
      or (codepoint >= 0x2648 and codepoint <= 0x2653)
      or codepoint == 0x267F
      or codepoint == 0x2693
      or codepoint == 0x26A1
      or (codepoint >= 0x26AA and codepoint <= 0x26AB)
      or (codepoint >= 0x26BD and codepoint <= 0x26BE)
      or (codepoint >= 0x26C4 and codepoint <= 0x26C5)
      or codepoint == 0x26CE
      or codepoint == 0x26D4
      or codepoint == 0x26EA
      or (codepoint >= 0x26F2 and codepoint <= 0x26F3)
      or codepoint == 0x26F5
      or codepoint == 0x26FA
      or codepoint == 0x26FD
      or codepoint == 0x2705
      or (codepoint >= 0x270A and codepoint <= 0x270B)
      or codepoint == 0x2728
      or codepoint == 0x274C
      or codepoint == 0x274E
      or (codepoint >= 0x2753 and codepoint <= 0x2755)
      or codepoint == 0x2757
      or (codepoint >= 0x2795 and codepoint <= 0x2797)
      or codepoint == 0x27B0
      or codepoint == 0x27BF
      or (codepoint >= 0x2B1B and codepoint <= 0x2B1C)
      or codepoint == 0x2B50
      or codepoint == 0x2B55
end

function Str(value)
  local codepoints = {}
  for _, codepoint in utf8.codes(value.text) do
    table.insert(codepoints, codepoint)
  end

  local result = {}
  local plain = {}
  local emoji = {}
  local function flush_plain()
    if #plain > 0 then
      table.insert(result, pandoc.Str(table.concat(plain)))
      plain = {}
    end
  end
  local function flush_emoji()
    if #emoji > 0 then
      table.insert(result, pandoc.RawInline('latex', '{\\chatjEmojiFont ' .. table.concat(emoji) .. '}'))
      emoji = {}
    end
  end

  for index, codepoint in ipairs(codepoints) do
    local character = utf8.char(codepoint)
    local next_codepoint = codepoints[index + 1]
    local starts_emoji = is_default_emoji(codepoint)
        or next_codepoint == 0xFE0F
        or next_codepoint == 0x20E3
    local is_emoji_tag = codepoint >= 0xE0020 and codepoint <= 0xE007F
    local continues_emoji = #emoji > 0
        and (codepoint == 0xFE0F or codepoint == 0x200D or codepoint == 0x20E3 or is_emoji_tag)
    if starts_emoji or continues_emoji then
      flush_plain()
      table.insert(emoji, character)
    else
      flush_emoji()
      table.insert(plain, character)
    end
  end
  flush_emoji()
  flush_plain()
  return #result == 1 and result[1] or result
end

function Table(table)
  local column_count = #table.colspecs
  if column_count == 0 then
    return table
  end
  for _, column in ipairs(table.colspecs) do
    if column[2] and column[2] > 0 then
      return table
    end
  end

  local width = 1 / column_count
  for index, column in ipairs(table.colspecs) do
    table.colspecs[index] = { column[1], width }
  end
  return table
end

function Image(image)
  local normalized_source = trim(image.src)
  local mermaid_target = normalized_source:match("^mermaid%-%d+%-%d+%.png$") ~= nil
  local mermaid_size = trim(image.title or ""):match("^chat4j%-mermaid%-(%a+)$")
  local valid_mermaid_size = mermaid_size == "small" or mermaid_size == "medium" or mermaid_size == "large"
  if mermaid_target and not valid_mermaid_size then
    return pandoc.Str("Image omitted")
  end

  if is_managed_image(image.src) then
    local smiles_size = trim(image.title or ""):match("^chat4j%-smiles%-(%a+)$")
    if smiles_size == "small" then
      image.attributes.width = "34%"
    elseif smiles_size == "medium" then
      image.attributes.width = "55%"
    elseif smiles_size == "large" then
      image.attributes.width = "80%"
    end
    if smiles_size ~= nil then
      image.title = ""
    end

    if mermaid_target and mermaid_size == "small" then
      image.attributes.width = "34%"
    elseif mermaid_target and mermaid_size == "medium" then
      image.attributes.width = "62%"
    elseif mermaid_target and mermaid_size == "large" then
      image.attributes.width = "100%"
    end
    if mermaid_target and mermaid_size ~= nil then
      image.title = ""
    end
    return image
  end
  if is_safe_http(image.src) then
    local label = pandoc.utils.stringify(image.caption)
    if label == "" then
      label = "Remote image"
    end
    return pandoc.Link({pandoc.Str(label .. " — remote image")}, trim(image.src))
  end
  return pandoc.Str("Image omitted")
end

function Link(link)
  if not is_safe_external_link(link.target) then
    return link.content
  end

  local label = trim(pandoc.utils.stringify(link.content))
  if is_safe_http(link.target) and label:match("^[1-9]%d?%d?$") then
    link.content = { pandoc.Str("[" .. label .. "]") }
    return pandoc.Superscript({ link })
  end
  return link
end

local safe_math_commands = {
  text = true, textrm = true, textsf = true, texttt = true,
  mathrm = true, mathbf = true, mathit = true, mathsf = true, mathtt = true,
  mathcal = true, mathbb = true, mathfrak = true, boldsymbol = true,
  operatorname = true, boxed = true, displaystyle = true,
  frac = true, dfrac = true, tfrac = true, sqrt = true,
  sum = true, prod = true, int = true, iint = true, iiint = true, oint = true,
  lim = true, min = true, max = true, log = true, ln = true,
  sin = true, cos = true, tan = true, exp = true,
  left = true, right = true, middle = true,
  big = true, Big = true, bigg = true, Bigg = true,
  overline = true, underline = true, hat = true, widehat = true,
  bar = true, vec = true, dot = true, ddot = true,
  overset = true, underset = true, stackrel = true,
  to = true, mapsto = true, rightarrow = true, leftarrow = true,
  leftrightarrow = true, longrightarrow = true, longleftarrow = true,
  longleftrightarrow = true, xrightarrow = true, xleftarrow = true,
  Rightarrow = true, Leftarrow = true, Leftrightarrow = true,
  implies = true, iff = true,
  cdot = true, times = true, div = true, pm = true, mp = true,
  le = true, leq = true, ge = true, geq = true, ne = true, neq = true,
  approx = true, equiv = true, sim = true, simeq = true, propto = true,
  ["in"] = true, notin = true, ni = true, subset = true, subseteq = true,
  supset = true, supseteq = true, cup = true, cap = true, setminus = true,
  infty = true, partial = true, nabla = true, forall = true, exists = true,
  neg = true, land = true, lor = true,
  alpha = true, beta = true, gamma = true, delta = true, epsilon = true,
  varepsilon = true, zeta = true, eta = true, theta = true, vartheta = true,
  iota = true, kappa = true, lambda = true, mu = true, nu = true, ell = true,
  xi = true, pi = true, varpi = true, rho = true, varrho = true,
  sigma = true, varsigma = true, tau = true, upsilon = true, phi = true,
  varphi = true, chi = true, psi = true, omega = true,
  Gamma = true, Delta = true, Theta = true, Lambda = true, Xi = true,
  Pi = true, Sigma = true, Upsilon = true, Phi = true, Psi = true, Omega = true,
  quad = true, qquad = true, large = true, Large = true, LARGE = true,
  huge = true, Huge = true, begin = true, ["end"] = true
}

local safe_math_control_symbols = {
  ["\\"] = true, ["|"] = true, [","] = true, [";"] = true,
  [":"] = true, ["!"] = true, [" "] = true,
  ["{"] = true, ["}"] = true, ["_"] = true
}

local one_group_math_commands = {
  text = true, textrm = true, textsf = true, texttt = true,
  mathrm = true, mathbf = true, mathit = true, mathsf = true, mathtt = true,
  mathcal = true, mathbb = true, mathfrak = true, boldsymbol = true,
  operatorname = true, boxed = true,
  overline = true, underline = true, hat = true, widehat = true,
  bar = true, vec = true, dot = true, ddot = true
}

local two_group_math_commands = {
  frac = true, dfrac = true, tfrac = true,
  overset = true, underset = true, stackrel = true
}

local function skip_math_spaces(source, index)
  while source:sub(index, index):match("%s") do
    index = index + 1
  end
  return index
end

local function math_group_end(source, opening_index)
  if source:sub(opening_index, opening_index) ~= "{" then
    return nil
  end
  local depth = 0
  local escaped = false
  for index = opening_index, #source do
    local character = source:sub(index, index)
    if escaped then
      escaped = false
    elseif character == "\\" then
      escaped = true
    elseif character == "{" then
      depth = depth + 1
    elseif character == "}" then
      depth = depth - 1
      if depth == 0 then
        return index
      end
    end
  end
  return nil
end

local function has_required_math_groups(source, command_end, count)
  local index = command_end + 1
  for _ = 1, count do
    index = skip_math_spaces(source, index)
    local closing_index = math_group_end(source, index)
    if closing_index == nil then
      return false
    end
    index = closing_index + 1
  end
  return true
end

local function has_valid_sqrt_argument(source, command_end)
  local index = skip_math_spaces(source, command_end + 1)
  if source:sub(index, index) == "[" then
    local closing_index = source:find("]", index + 1, true)
    if closing_index == nil then
      return false
    end
    index = skip_math_spaces(source, closing_index + 1)
  end
  return math_group_end(source, index) ~= nil
end

local function has_valid_xarrow_argument(source, command_end)
  local index = skip_math_spaces(source, command_end + 1)
  if source:sub(index, index) == "[" then
    local closing_index = source:find("]", index + 1, true)
    if closing_index == nil then
      return false
    end
    index = skip_math_spaces(source, closing_index + 1)
  end
  return math_group_end(source, index) ~= nil
end

local function has_valid_math_delimiter(source, command_end)
  local index = skip_math_spaces(source, command_end + 1)
  local delimiter = source:sub(index, index)
  if delimiter:match("[%(%)%[%]|%.]") then
    return true
  end
  return delimiter == "\\" and safe_math_control_symbols[source:sub(index + 1, index + 1)] == true
end

local function has_valid_math_scripts(source)
  local escaped = false
  for index = 1, #source do
    local character = source:sub(index, index)
    if escaped then
      escaped = false
    elseif character == "\\" then
      escaped = true
    elseif character == "_" or character == "^" then
      local argument_index = skip_math_spaces(source, index + 1)
      local argument = source:sub(argument_index, argument_index)
      if argument ~= "{" and argument ~= "\\" and not argument:match("[A-Za-z0-9+%=()%-]") then
        return false
      end
    end
  end
  return true
end

local function has_balanced_math_braces(source)
  local depth = 0
  local escaped = false
  for index = 1, #source do
    local character = source:sub(index, index)
    if escaped then
      escaped = false
    elseif character == "\\" then
      escaped = true
    elseif character == "{" then
      depth = depth + 1
      if depth > 64 then
        return false
      end
    elseif character == "}" then
      depth = depth - 1
      if depth < 0 then
        return false
      end
    end
  end
  return depth == 0 and not escaped
end

local function is_safe_math(source)
  if #source == 0 or #source > 20000 then
    return false
  end
  if source:find("[%z\1-\8\11\12\14-\31\127]")
      or source:find("[%%#$~]")
      or source:find("%^%^")
      or not has_balanced_math_braces(source)
      or not has_valid_math_scripts(source) then
    return false
  end

  local array_depth = 0
  local delimiter_depth = 0
  local index = 1
  while index <= #source do
    local slash = source:find("\\", index, true)
    local plain_segment = slash == nil and source:sub(index) or source:sub(index, slash - 1)
    if array_depth == 0 and plain_segment:find("&", 1, true) then
      return false
    end
    if slash == nil then
      break
    end
    local next_character = source:sub(slash + 1, slash + 1)
    if next_character == "" then
      return false
    end
    if next_character:match("%a") then
      local command_end = slash + 1
      while source:sub(command_end + 1, command_end + 1):match("%a") do
        command_end = command_end + 1
      end
      local command = source:sub(slash + 1, command_end)
      if not safe_math_commands[command] then
        return false
      end
      if one_group_math_commands[command] and not has_required_math_groups(source, command_end, 1) then
        return false
      end
      if two_group_math_commands[command] and not has_required_math_groups(source, command_end, 2) then
        return false
      end
      if command == "sqrt" and not has_valid_sqrt_argument(source, command_end) then
        return false
      end
      if (command == "xrightarrow" or command == "xleftarrow")
          and not has_valid_xarrow_argument(source, command_end) then
        return false
      end
      if command == "left" then
        if not has_valid_math_delimiter(source, command_end) then
          return false
        end
        delimiter_depth = delimiter_depth + 1
      elseif command == "middle" then
        if delimiter_depth == 0 or not has_valid_math_delimiter(source, command_end) then
          return false
        end
      elseif command == "right" then
        if delimiter_depth == 0 or not has_valid_math_delimiter(source, command_end) then
          return false
        end
        delimiter_depth = delimiter_depth - 1
      elseif command == "begin" then
        if source:sub(command_end + 1, command_end + 7) ~= "{array}" then
          return false
        end
        local specification_start = command_end + 8
        local specification_end = source:find("}", specification_start + 1, true)
        if source:sub(specification_start, specification_start) ~= "{" or specification_end == nil then
          return false
        end
        local specification = source:sub(specification_start + 1, specification_end - 1):gsub("%s", "")
        if specification == "" or not specification:match("^[lcr|]+$") or not specification:find("[lcr]") then
          return false
        end
        array_depth = array_depth + 1
      elseif command == "end" then
        if source:sub(command_end + 1, command_end + 7) ~= "{array}" then
          return false
        end
        array_depth = array_depth - 1
        if array_depth < 0 then
          return false
        end
      end
      index = command_end + 1
    else
      if not safe_math_control_symbols[next_character] then
        return false
      end
      index = slash + 2
    end
  end
  if array_depth ~= 0 or delimiter_depth ~= 0 then
    return false
  end
  return true
end

function Math(math)
  if is_safe_math(math.text) then
    return math
  end
  local delimiter = math.mathtype == "DisplayMath" and "$$" or "$"
  return pandoc.Code(delimiter .. math.text .. delimiter)
end
