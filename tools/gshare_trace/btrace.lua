-- Instruction-stream tracer: records every read whose address == PC (opcode + extension words).
-- Record 10 bytes: <I4 curpc> <I4 pc> <I2 word>
local out_prefix = os.getenv("BTRACE_OUT") or "/var/tmp/btrace"
local windows = {}
for spec in (os.getenv("BTRACE_WINDOWS") or "2:2000000"):gmatch("[^,]+") do
  local t, n = spec:match("^%s*([%d%.]+)%s*:%s*(%d+)%s*$")
  windows[#windows+1] = { t = tonumber(t), budget = tonumber(n) }
end
local function simtime() local t = manager.machine.time; return t.seconds + t.attoseconds / 1e18 end
local wi, active, tap, fh = 1, nil, nil, nil
local lastcp = -1
local buf, bn, count, ninst = {}, 0, 0, 0
local pcs, curpcs
local pack = string.pack
local SH = {}
SH[0xffff0000] = {0,16}; SH[0x0000ffff] = {2,0}; SH[0xffffffff] = {0,16}
local function flush() if bn > 0 then fh:write(table.concat(buf, "", 1, bn)); bn = 0 end end
local function rd(offset, data, mask)
  local s = SH[mask]; if not s then return end
  local a = offset + s[1]
  local pc = pcs.value & 0xffffffff
  if a ~= pc then
    if mask == 0xffffffff and offset + 2 == pc then a = pc; s = {2,0} else return end
  end
  local cp = curpcs.value & 0xffffffff
  bn = bn + 1
  buf[bn] = pack("<I4I4I2", cp, pc, (data >> s[2]) & 0xffff)
  if cp ~= lastcp or cp == pc then ninst = ninst + 1 end
  lastcp = cp
  count = count + 1
  if bn >= 16384 then flush() end
  if ninst >= active.budget then
    flush(); fh:close(); fh = nil
    print(string.format("[btrace] window %d closed: %d recs %d inst t=%.3f", active.idx, count, ninst, simtime()))
    io.stdout:flush(); active = nil
  end
end
emu.register_periodic(function()
  local m = manager.machine
  if not m or active ~= nil or wi > #windows then return end
  local w = windows[wi]
  if simtime() < w.t then return end
  wi = wi + 1; w.idx = wi - 1; active = w; count = 0; ninst = 0; bn = 0
  local cpu = m.devices[":maincpu"]
  pcs = cpu.state["PC"]; curpcs = cpu.state["CURPC"]
  fh = io.open(string.format("%s.w%d.bin", out_prefix, w.idx), "wb")
  if tap == nil then tap = cpu.spaces["program"]:install_read_tap(0, 0xFFFFFFFF, "btrace_r", function(o, d, mk) if active then rd(o, d, mk) end end) end
  print(string.format("[btrace] window %d armed t=%.3f", w.idx, simtime())); io.stdout:flush()
end)
local lastapp
emu.register_periodic(function()
  local m = manager.machine
  if not m then return end
  local sp = m.devices[":maincpu"].spaces["program"]
  local ok, name = pcall(function()
    local n = sp:read_u8(0x910); if n == 0 or n > 31 then return nil end
    local s = ""; for i = 1, n do s = s .. string.char(sp:read_u8(0x910 + i)) end; return s end)
  if ok and name and name ~= lastapp and #name > 0 then lastapp = name
    print(string.format("[phase] t=%.2fs CurApName=%q", simtime(), name)); io.stdout:flush() end
end)
