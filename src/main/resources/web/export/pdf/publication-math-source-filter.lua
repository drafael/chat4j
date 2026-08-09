function Math(math)
  local delimiter = math.mathtype == "DisplayMath" and "$$" or "$"
  return pandoc.Code(delimiter .. math.text .. delimiter)
end
