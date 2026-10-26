<?php
require __DIR__ . '/inc/bootstrap.php';
if (!empty($_SESSION['role'])) audit('logout');
logout_now();
redirect('login.php');
