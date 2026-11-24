package com.exampletest.mobilestudiotest.ai

/**
 * Deterministic, on-device starter builder. It does not call a network service or consume AI credits.
 * The generated Luau is intentionally limited to a few predictable GUI layouts.
 */
object ForgeCommandEngine {
    data class BuildPlan(val title: String, val description: String, val code: String)

    fun build(command: String): BuildPlan? {
        val request = command.trim().lowercase()
        if (request.isEmpty()) return null

        val kind = when {
            listOf("shop", "store", "buy").any { it in request } -> "Shop"
            listOf("inventory", "backpack", "items").any { it in request } -> "Inventory"
            listOf("hud", "health bar", "coins", "coin counter", "status").any { it in request } -> "HUD"
            listOf("obby", "obstacle course", "parkour").any { it in request } -> "Obby"
            listOf("arena", "battle map", "pvp map").any { it in request } -> "Arena"
            listOf("map", "level", "world", "platform").any { it in request } -> "Map"
            listOf("gui", "interface", "menu", "button", "screen").any { it in request } -> "Menu"
            else -> return null
        }
        val title = when (kind) {
            "Shop" -> "ITEM SHOP"
            "Inventory" -> "INVENTORY"
            "HUD" -> "PLAYER STATUS"
            else -> command.trim().take(28).uppercase().ifBlank { "APEX MENU" }
        }
        val button = when (kind) {
            "Shop" -> "Browse Items"
            "Inventory" -> "Close Inventory"
            "HUD" -> "Refresh"
            else -> "START"
        }
        val safeTitle = escape(title)
        val safeButton = escape(button)
        val guiName = "Forge${kind}Gui"
        val script = if (kind in setOf("Map", "Arena", "Obby")) ForgeWorldTemplates.script(kind) else """
local StarterGui = game:GetService("StarterGui")
local old = StarterGui:FindFirstChild("$guiName")
if old then old:Destroy() end

local gui = Instance.new("ScreenGui")
gui.Name = "$guiName"
gui.ResetOnSpawn = false
gui.IgnoreGuiInset = true
gui.Parent = StarterGui

local panel = Instance.new("Frame")
panel.Name = "MainPanel"
panel.AnchorPoint = Vector2.new(0.5, 0.5)
panel.Position = UDim2.new(0.5, 0, 0.5, 0)
panel.Size = UDim2.new(0, 310, 0, 210)
panel.BackgroundColor3 = Color3.fromRGB(24, 25, 30)
panel.BorderSizePixel = 0
panel.Parent = gui

local corner = Instance.new("UICorner")
corner.CornerRadius = UDim.new(0, 14)
corner.Parent = panel

local stroke = Instance.new("UIStroke")
stroke.Color = Color3.fromRGB(145, 35, 48)
stroke.Thickness = 2
stroke.Parent = panel

local heading = Instance.new("TextLabel")
heading.Name = "Title"
heading.BackgroundTransparency = 1
heading.Position = UDim2.new(0, 16, 0, 18)
heading.Size = UDim2.new(1, -32, 0, 42)
heading.Font = Enum.Font.GothamBold
heading.Text = "$safeTitle"
heading.TextColor3 = Color3.fromRGB(245, 245, 248)
heading.TextSize = 22
heading.Parent = panel

local subtitle = Instance.new("TextLabel")
subtitle.Name = "Subtitle"
subtitle.BackgroundTransparency = 1
subtitle.Position = UDim2.new(0, 16, 0, 66)
subtitle.Size = UDim2.new(1, -32, 0, 36)
subtitle.Font = Enum.Font.Gotham
subtitle.Text = "Created with FORGE offline builder"
subtitle.TextColor3 = Color3.fromRGB(175, 178, 188)
subtitle.TextSize = 12
subtitle.Parent = panel

local action = Instance.new("TextButton")
action.Name = "ActionButton"
action.Position = UDim2.new(0, 18, 1, -66)
action.Size = UDim2.new(1, -36, 0, 46)
action.BackgroundColor3 = Color3.fromRGB(145, 35, 48)
action.BorderSizePixel = 0
action.Font = Enum.Font.GothamBold
action.Text = "$safeButton"
action.TextColor3 = Color3.fromRGB(255, 255, 255)
action.TextSize = 15
action.Parent = panel

local buttonCorner = Instance.new("UICorner")
buttonCorner.CornerRadius = UDim.new(0, 9)
buttonCorner.Parent = action

local client = Instance.new("LocalScript")
client.Name = "ForgeButtonLogic"
client.Source = [[
local button = script.Parent:WaitForChild("ActionButton")
button.MouseButton1Click:Connect(function()
    button.Text = "READY"
    task.delay(1.2, function()
        if button and button.Parent then button.Text = "$safeButton" end
    end)
end)
]]
client.Parent = panel

print("FORGE created $guiName in StarterGui. Press Play to preview it.")
""".trimIndent()
        val description = if (kind in setOf("Map", "Arena", "Obby")) "Built a starter 3D $kind layout in Workspace.FORGE_Generated. Open Explorer to inspect it." else "Built a dark $kind interface with a sample button. Find it under StarterGui and press Play to preview."
        return BuildPlan("$kind created", description, script)
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
