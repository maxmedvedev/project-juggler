package com.projectjuggler.core

interface NotificationHandler {
    fun showInfoNotification(message: String)
    fun showErrorNotification(message: String)
}