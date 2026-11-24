package com.exampletest.mobilestudiotest.ai

/**
 * Small deterministic 3D world templates generated entirely on-device.
 * These are starter layouts, not full playable game systems.
 */
object ForgeWorldTemplates {
    fun script(kind: String): String {
        val template = kind.takeIf { it in setOf("Map", "Arena", "Obby") } ?: "Map"
        return """
local Workspace = game:GetService("Workspace")
local old = Workspace:FindFirstChild("FORGE_Generated")
if old then old:Destroy() end

local model = Instance.new("Model")
model.Name = "FORGE_Generated"
model.Parent = Workspace

local function makePart(name, size, position, color)
    local part = Instance.new("Part")
    part.Name = name
    part.Size = size
    part.Position = position
    part.Anchored = true
    part.CanCollide = true
    part.Color = color
    part.Parent = model
    return part
end

local dark = Color3.fromRGB(35, 37, 44)
local red = Color3.fromRGB(145, 35, 48)
local stone = Color3.fromRGB(70, 73, 82)

makePart("MainFloor", Vector3.new(120, 2, 120), Vector3.new(0, -1, 0), dark)

if "$template" == "Arena" then
    makePart("NorthWall", Vector3.new(120, 20, 2), Vector3.new(0, 9, -60), stone)
    makePart("SouthWall", Vector3.new(120, 20, 2), Vector3.new(0, 9, 60), stone)
    makePart("EastWall", Vector3.new(2, 20, 120), Vector3.new(60, 9, 0), stone)
    makePart("WestWall", Vector3.new(2, 20, 120), Vector3.new(-60, 9, 0), stone)
    makePart("CenterPlatform", Vector3.new(22, 3, 22), Vector3.new(0, 1, 0), red)
    for i = 1, 4 do
        local x = (i % 2 == 0) and 32 or -32
        local z = (i <= 2) and 30 or -30
        makePart("Cover_" .. i, Vector3.new(12, 8, 5), Vector3.new(x, 3, z), stone)
    end
elseif "$template" == "House" then
    makePart("HouseFloor", Vector3.new(30, 2, 24), Vector3.new(0, 1, 0), stone)
    makePart("FrontWall", Vector3.new(30, 14, 2), Vector3.new(0, 8, -11), dark)
    makePart("BackWall", Vector3.new(30, 14, 2), Vector3.new(0, 8, 11), dark)
    makePart("LeftWall", Vector3.new(2, 14, 20), Vector3.new(-14, 8, 0), dark)
    makePart("RightWall", Vector3.new(2, 14, 20), Vector3.new(14, 8, 0), dark)
    makePart("Roof", Vector3.new(34, 3, 28), Vector3.new(0, 16, 0), red)
    makePart("Door", Vector3.new(5, 9, 1), Vector3.new(0, 5.5, -12.2), red)
    makePart("WindowLeft", Vector3.new(5, 5, 1), Vector3.new(-9, 9, -12.2), stone)
    makePart("WindowRight", Vector3.new(5, 5, 1), Vector3.new(9, 9, -12.2), stone)
elseif "$template" == "Tower" then
    makePart("TowerBase", Vector3.new(24, 3, 24), Vector3.new(0, 1, 0), stone)
    for i = 1, 5 do
        local y = i * 12
        makePart("TowerLevel_" .. i, Vector3.new(20 - i, 3, 20 - i), Vector3.new(0, y, 0), (i % 2 == 0) and red or dark)
        makePart("TowerPillarA_" .. i, Vector3.new(2, 12, 2), Vector3.new(-8, y - 6, -8), stone)
        makePart("TowerPillarB_" .. i, Vector3.new(2, 12, 2), Vector3.new(8, y - 6, -8), stone)
        makePart("TowerPillarC_" .. i, Vector3.new(2, 12, 2), Vector3.new(-8, y - 6, 8), stone)
        makePart("TowerPillarD_" .. i, Vector3.new(2, 12, 2), Vector3.new(8, y - 6, 8), stone)
    end
    makePart("TowerTop", Vector3.new(16, 4, 16), Vector3.new(0, 64, 0), red)
elseif "$template" == "Bridge" then
    for i = 1, 12 do
        local x = (i - 1) * 8 - 44
        makePart("BridgeDeck_" .. i, Vector3.new(9, 2, 16), Vector3.new(x, 8, 0), stone)
        makePart("BridgeRailLeft_" .. i, Vector3.new(9, 3, 1), Vector3.new(x, 10.5, -7.5), red)
        makePart("BridgeRailRight_" .. i, Vector3.new(9, 3, 1), Vector3.new(x, 10.5, 7.5), red)
    end
    makePart("BridgeSupportA", Vector3.new(5, 16, 5), Vector3.new(-24, 0, 0), dark)
    makePart("BridgeSupportB", Vector3.new(5, 16, 5), Vector3.new(24, 0, 0), dark)
elseif "$template" == "Obby" then
    for i = 1, 12 do
        local x = (i - 1) * 8 - 44
        local z = (i % 2 == 0) and 10 or -10
        makePart("JumpPlatform_" .. i, Vector3.new(7, 2, 7), Vector3.new(x, i * 2, z), (i % 2 == 0) and red or stone)
    end
    makePart("FinishPlatform", Vector3.new(16, 2, 16), Vector3.new(56, 26, 0), red)
else
    makePart("SpawnPlatform", Vector3.new(24, 2, 24), Vector3.new(0, 1, 0), red)
    for i = 1, 8 do
        local angle = (i - 1) * 0.785
        local x = math.cos(angle) * 34
        local z = math.sin(angle) * 34
        makePart("PathPlatform_" .. i, Vector3.new(12, 2, 12), Vector3.new(x, 1, z), stone)
    end
end

print("FORGE created $template in Workspace.FORGE_Generated")
""".trimIndent()
    }
}
